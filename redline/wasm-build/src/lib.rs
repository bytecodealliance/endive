//! Thin FFI bridge exposing Cranelift's FunctionBuilder API as flat Wasm exports.
//!
//! All handles (block_id, value_id, var_id) are opaque u32.
//! The Java side tracks them explicitly and passes them to subsequent calls.
//! Each export immediately calls the corresponding Cranelift API — no accumulation.

use cranelift_codegen::ir::condcodes::IntCC;
use cranelift_codegen::ir::types;
use cranelift_codegen::ir::{AbiParam, AtomicRmwOp, BlockArg, BlockCall, Function, InstBuilder, MemFlags, Signature, UserFuncName};
use cranelift_codegen::isa::{self, CallConv, TargetIsa};
use cranelift_codegen::settings::{self, Configurable};
use cranelift_codegen::Context;
use cranelift_control::ControlPlane;
use cranelift_frontend::{FunctionBuilder, FunctionBuilderContext, Variable};
use std::sync::Arc;
use target_lexicon::Triple;

// --- Global state (single-threaded Wasm, safe) ---

static mut ISA: Option<Arc<dyn TargetIsa>> = None;
static mut COMPILED_CODE: Vec<u8> = Vec::new();
static mut TRAMPOLINE_SIG: Option<Signature> = None;

/// Session holds the FunctionBuilder and its backing data.
/// We use raw pointers to keep FunctionBuilder alive across FFI calls,
/// working around the borrow checker. Safe because Wasm is single-threaded.
struct Session {
    // These are heap-allocated and pinned so their addresses are stable.
    func: Box<Function>,
    builder_ctx: Box<FunctionBuilderContext>,
    // The FunctionBuilder borrows func and builder_ctx.
    // We store it as a raw pointer to avoid lifetime issues.
    builder: *mut FunctionBuilder<'static>,
    // Lookup tables: synthetic u32 IDs -> real Cranelift handles
    blocks: Vec<cranelift_codegen::ir::Block>,
    variables: Vec<Variable>,
    values: Vec<cranelift_codegen::ir::Value>,
    sig_refs: Vec<cranelift_codegen::ir::SigRef>,
    call_args: Vec<cranelift_codegen::ir::Value>,
    // Temp signature builder
    sig_builder: Option<Signature>,
    jump_tables: Vec<cranelift_codegen::ir::JumpTable>,
    br_table_targets: Vec<cranelift_codegen::ir::Block>,
}

static mut SESSION: Option<Session> = None;

fn wasm_type_to_clif(ty: u32) -> cranelift_codegen::ir::Type {
    match ty {
        0 => types::I32,
        1 => types::I64,
        2 => types::F32,
        3 => types::F64,
        _ => panic!("Unknown wasm type: {}", ty),
    }
}

/// Get the active FunctionBuilder.
fn b() -> &'static mut FunctionBuilder<'static> {
    let session = unsafe { SESSION.as_ref().expect("No active session") };
    assert!(!session.builder.is_null(), "Builder used after compile()");
    unsafe { &mut *session.builder }
}

/// Get the session.
fn s() -> &'static mut Session {
    unsafe { SESSION.as_mut().unwrap() }
}

// --- malloc/free using Rust's global allocator ---

#[no_mangle]
pub extern "C" fn wasm_malloc(size: u32) -> *mut u8 {
    if size == 0 {
        return 8 as *mut u8; // Return aligned dangling pointer for zero-sized alloc
    }
    let layout = std::alloc::Layout::from_size_align(size as usize, 8).unwrap();
    unsafe { std::alloc::alloc(layout) }
}

#[no_mangle]
pub extern "C" fn wasm_free(ptr: *mut u8, size: u32) {
    if size == 0 {
        return; // No-op for zero-sized alloc (matches wasm_malloc guard)
    }
    let layout = std::alloc::Layout::from_size_align(size as usize, 8).unwrap();
    unsafe { std::alloc::dealloc(ptr, layout) }
}

// --- Exported functions ---

#[no_mangle]
pub extern "C" fn init(target_ptr: *const u8, target_len: u32) {
    let target_str = unsafe {
        std::str::from_utf8(std::slice::from_raw_parts(target_ptr, target_len as usize)).unwrap()
    };
    let mut flag_builder = settings::builder();
    flag_builder.set("opt_level", "speed").unwrap();
    flag_builder.set("is_pic", "false").unwrap();
    flag_builder.set("preserve_frame_pointers", "true").unwrap();
    let flags = settings::Flags::new(flag_builder);
    let triple: Triple = target_str.parse().expect("Failed to parse target triple");
    let arch = triple.architecture;
    let mut isa_builder = isa::lookup(triple).expect("Unsupported target");
    // Enable SSE4.1+ to avoid libcalls for ceil/floor/trunc/nearest
    if arch == target_lexicon::Architecture::X86_64 {
        isa_builder.enable("has_sse3").unwrap();
        isa_builder.enable("has_ssse3").unwrap();
        isa_builder.enable("has_sse41").unwrap();
        isa_builder.enable("has_sse42").unwrap();
    }
    let isa = isa_builder
        .finish(flags)
        .expect("Failed to create ISA");
    unsafe {
        ISA = Some(isa);
    }
}

/// Create a new function and its FunctionBuilder. Call add_param_type/add_return_type
/// BEFORE build_function.
#[no_mangle]
pub extern "C" fn create_function() {
    let func = Box::new(Function::with_name_signature(
        UserFuncName::user(0, 0),
        Signature::new(CallConv::Tail),
    ));
    let builder_ctx = Box::new(FunctionBuilderContext::new());

    unsafe {
        SESSION = Some(Session {
            func,
            builder_ctx,
            builder: std::ptr::null_mut(),
            blocks: Vec::new(),
            variables: Vec::new(),
            values: Vec::new(),
            sig_refs: Vec::new(),
            call_args: Vec::new(),
            sig_builder: None,
            jump_tables: Vec::new(),
            br_table_targets: Vec::new(),
        });
    }
}

#[no_mangle]
pub extern "C" fn add_param_type(wasm_type: u32) {
    s().func.signature.params.push(AbiParam::new(wasm_type_to_clif(wasm_type)));
}

#[no_mangle]
pub extern "C" fn add_return_type(wasm_type: u32) {
    s().func.signature.returns.push(AbiParam::new(wasm_type_to_clif(wasm_type)));
}

/// Finalize the signature and create the FunctionBuilder.
/// Must be called after all add_param_type/add_return_type and before any emit calls.
#[no_mangle]
pub extern "C" fn build_function() {
    let session = s();
    // Create builder from raw pointers to avoid lifetime issues.
    // Safe: single-threaded, session outlives builder, and we only drop in compile().
    let func_ptr: *mut Function = &mut *session.func;
    let ctx_ptr: *mut FunctionBuilderContext = &mut *session.builder_ctx;
    let builder = unsafe {
        FunctionBuilder::new(&mut *func_ptr, &mut *ctx_ptr)
    };
    let builder_box = Box::new(builder);
    session.builder = Box::into_raw(builder_box) as *mut FunctionBuilder<'static>;
}

// --- Blocks ---

#[no_mangle]
pub extern "C" fn create_block() -> u32 {
    let block = b().create_block();
    let session = s();
    let id = session.blocks.len() as u32;
    session.blocks.push(block);
    id
}

#[no_mangle]
pub extern "C" fn switch_to_block(block_id: u32) {
    let block = s().blocks[block_id as usize];
    b().switch_to_block(block);
}

#[no_mangle]
pub extern "C" fn seal_block(block_id: u32) {
    let block = s().blocks[block_id as usize];
    b().seal_block(block);
}

#[no_mangle]
pub extern "C" fn seal_all_blocks() {
    b().seal_all_blocks();
}

#[no_mangle]
pub extern "C" fn append_block_params_for_func_params(block_id: u32) {
    let block = s().blocks[block_id as usize];
    b().append_block_params_for_function_params(block);
}

// --- Variables ---

#[no_mangle]
pub extern "C" fn declare_var(wasm_type: u32) -> u32 {
    let var = b().declare_var(wasm_type_to_clif(wasm_type));
    let session = s();
    let id = session.variables.len() as u32;
    session.variables.push(var);
    id
}

#[no_mangle]
pub extern "C" fn def_var(var_id: u32, val_id: u32) {
    let var = s().variables[var_id as usize];
    let val = s().values[val_id as usize];
    b().def_var(var, val);
}

#[no_mangle]
pub extern "C" fn use_var(var_id: u32) -> u32 {
    let var = s().variables[var_id as usize];
    let val = b().use_var(var);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

// --- Params ---

#[no_mangle]
pub extern "C" fn func_param(block_id: u32, index: u32) -> u32 {
    let block = s().blocks[block_id as usize];
    let params = b().block_params(block);
    let val = params[index as usize];
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

// --- Constants ---

#[no_mangle]
pub extern "C" fn emit_iconst_32(val: i32) -> u32 {
    let v = b().ins().iconst(types::I32, val as i64);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(v);
    id
}

#[no_mangle]
pub extern "C" fn emit_get_stack_pointer() -> u32 {
    let v = b().ins().get_stack_pointer(types::I64);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(v);
    id
}

#[no_mangle]
pub extern "C" fn emit_iconst_64(val_lo: u32, val_hi: u32) -> u32 {
    let val = (val_lo as i64) | ((val_hi as i64) << 32);
    let v = b().ins().iconst(types::I64, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(v);
    id
}

#[no_mangle]
pub extern "C" fn emit_f32const(bits: u32) -> u32 {
    let v = b().ins().f32const(f32::from_bits(bits));
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(v);
    id
}

#[no_mangle]
pub extern "C" fn emit_f64const(bits_lo: u32, bits_hi: u32) -> u32 {
    let bits = (bits_lo as u64) | ((bits_hi as u64) << 32);
    let v = b().ins().f64const(f64::from_bits(bits));
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(v);
    id
}

// --- Arithmetic ---

#[no_mangle]
pub extern "C" fn emit_iadd(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().iadd(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_isub(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().isub(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_imul(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().imul(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_sdiv(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().sdiv(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_udiv(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().udiv(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_srem(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().srem(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_urem(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().urem(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_band(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().band(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_bor(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().bor(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_bxor(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().bxor(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_ishl(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().ishl(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_ushr(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().ushr(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_sshr(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().sshr(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_rotl(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().rotl(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_rotr(a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let r = b().ins().rotr(va, vb);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_clz(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().clz(va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_ctz(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().ctz(va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_popcnt(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().popcnt(va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_eqz(a: u32) -> u32 {
    let va = s().values[a as usize];
    let ty = b().func.dfg.value_type(va);
    let zero = b().ins().iconst(ty, 0);
    let cmp = b().ins().icmp(IntCC::Equal, va, zero);
    // icmp returns I8, extend to I32 for Wasm compatibility
    let r = b().ins().uextend(types::I32, cmp);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// cc: 0=eq, 1=ne, 2=lt_s, 3=lt_u, 4=gt_s, 5=gt_u, 6=le_s, 7=le_u, 8=ge_s, 9=ge_u
#[no_mangle]
pub extern "C" fn emit_icmp(cc: u32, a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let cond = match cc {
        0 => IntCC::Equal,
        1 => IntCC::NotEqual,
        2 => IntCC::SignedLessThan,
        3 => IntCC::UnsignedLessThan,
        4 => IntCC::SignedGreaterThan,
        5 => IntCC::UnsignedGreaterThan,
        6 => IntCC::SignedLessThanOrEqual,
        7 => IntCC::UnsignedLessThanOrEqual,
        8 => IntCC::SignedGreaterThanOrEqual,
        9 => IntCC::UnsignedGreaterThanOrEqual,
        _ => panic!("Unknown icmp condition code: {}", cc),
    };
    let cmp = b().ins().icmp(cond, va, vb);
    // icmp returns I8, extend to I32 for Wasm compatibility
    let r = b().ins().uextend(types::I32, cmp);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Sign-extend i8 to i32
#[no_mangle]
pub extern "C" fn emit_sextend_8_32(a: u32) -> u32 {
    let va = s().values[a as usize];
    let truncated = b().ins().ireduce(types::I8, va);
    let r = b().ins().sextend(types::I32, truncated);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Sign-extend i16 to i32
#[no_mangle]
pub extern "C" fn emit_sextend_16_32(a: u32) -> u32 {
    let va = s().values[a as usize];
    let truncated = b().ins().ireduce(types::I16, va);
    let r = b().ins().sextend(types::I32, truncated);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- Memory ---

#[no_mangle]
pub extern "C" fn emit_load_i32(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I32, MemFlags::new(), effective, offset);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

#[no_mangle]
pub extern "C" fn emit_store_i32(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    b().ins().store(MemFlags::new(), vvalue, effective, offset);
}

// --- Memory: f32 ---

#[no_mangle]
pub extern "C" fn emit_load_f32(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::F32, MemFlags::new(), effective, offset);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

#[no_mangle]
pub extern "C" fn emit_store_f32(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    b().ins().store(MemFlags::new(), vvalue, effective, offset);
}

// --- Memory: f64 ---

#[no_mangle]
pub extern "C" fn emit_load_f64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::F64, MemFlags::new(), effective, offset);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

#[no_mangle]
pub extern "C" fn emit_store_f64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    b().ins().store(MemFlags::new(), vvalue, effective, offset);
}

// --- Memory: sub-word i32 loads ---

/// Load u8 and zero-extend to i32
#[no_mangle]
pub extern "C" fn emit_load_8u(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I8, MemFlags::new(), effective, offset);
    let r = b().ins().uextend(types::I32, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load i8 and sign-extend to i32
#[no_mangle]
pub extern "C" fn emit_load_8s(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I8, MemFlags::new(), effective, offset);
    let r = b().ins().sextend(types::I32, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load u16 and zero-extend to i32
#[no_mangle]
pub extern "C" fn emit_load_16u(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I16, MemFlags::new(), effective, offset);
    let r = b().ins().uextend(types::I32, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load i16 and sign-extend to i32
#[no_mangle]
pub extern "C" fn emit_load_16s(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I16, MemFlags::new(), effective, offset);
    let r = b().ins().sextend(types::I32, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Store low 8 bits of i32
#[no_mangle]
pub extern "C" fn emit_store_8(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let truncated = b().ins().ireduce(types::I8, vvalue);
    b().ins().store(MemFlags::new(), truncated, effective, offset);
}

/// Store low 16 bits of i32
#[no_mangle]
pub extern "C" fn emit_store_16(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let truncated = b().ins().ireduce(types::I16, vvalue);
    b().ins().store(MemFlags::new(), truncated, effective, offset);
}

// --- Memory: i64 sub-word loads ---

/// Load u8 and zero-extend to i64
#[no_mangle]
pub extern "C" fn emit_load_8u_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I8, MemFlags::new(), effective, offset);
    let r = b().ins().uextend(types::I64, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load i8 and sign-extend to i64
#[no_mangle]
pub extern "C" fn emit_load_8s_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I8, MemFlags::new(), effective, offset);
    let r = b().ins().sextend(types::I64, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load u16 and zero-extend to i64
#[no_mangle]
pub extern "C" fn emit_load_16u_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I16, MemFlags::new(), effective, offset);
    let r = b().ins().uextend(types::I64, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load i16 and sign-extend to i64
#[no_mangle]
pub extern "C" fn emit_load_16s_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I16, MemFlags::new(), effective, offset);
    let r = b().ins().sextend(types::I64, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load u32 and zero-extend to i64
#[no_mangle]
pub extern "C" fn emit_load_32u_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I32, MemFlags::new(), effective, offset);
    let r = b().ins().uextend(types::I64, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Load i32 and sign-extend to i64
#[no_mangle]
pub extern "C" fn emit_load_32s_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I32, MemFlags::new(), effective, offset);
    let r = b().ins().sextend(types::I64, val);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Store low 8 bits of i64
#[no_mangle]
pub extern "C" fn emit_store_8_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let truncated = b().ins().ireduce(types::I8, vvalue);
    b().ins().store(MemFlags::new(), truncated, effective, offset);
}

/// Store low 16 bits of i64
#[no_mangle]
pub extern "C" fn emit_store_16_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let truncated = b().ins().ireduce(types::I16, vvalue);
    b().ins().store(MemFlags::new(), truncated, effective, offset);
}

/// Store low 32 bits of i64
#[no_mangle]
pub extern "C" fn emit_store_32_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let truncated = b().ins().ireduce(types::I32, vvalue);
    b().ins().store(MemFlags::new(), truncated, effective, offset);
}

// --- Atomic helpers ---

fn effective_addr(base: u32, wasm_addr: u32, offset: i32) -> cranelift_codegen::ir::Value {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let addr = b().ins().iadd(vbase, extended);
    if offset != 0 {
        b().ins().iadd_imm(addr, offset as i64)
    } else {
        addr
    }
}

fn wasm_rmw_op(op: u32) -> AtomicRmwOp {
    match op {
        0 => AtomicRmwOp::Add,
        1 => AtomicRmwOp::Sub,
        2 => AtomicRmwOp::And,
        3 => AtomicRmwOp::Or,
        4 => AtomicRmwOp::Xor,
        5 => AtomicRmwOp::Xchg,
        _ => panic!("Unknown atomic RMW op: {}", op),
    }
}

fn push_val(val: cranelift_codegen::ir::Value) -> u32 {
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

// --- Atomic loads ---

#[no_mangle]
pub extern "C" fn emit_atomic_load_i32(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let val = b().ins().atomic_load(types::I32, MemFlags::new(), addr);
    push_val(val)
}

#[no_mangle]
pub extern "C" fn emit_atomic_load_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let val = b().ins().atomic_load(types::I64, MemFlags::new(), addr);
    push_val(val)
}

#[no_mangle]
pub extern "C" fn emit_atomic_load8_u_i32(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let val = b().ins().atomic_load(types::I8, MemFlags::new(), addr);
    let r = b().ins().uextend(types::I32, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_load16_u_i32(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let val = b().ins().atomic_load(types::I16, MemFlags::new(), addr);
    let r = b().ins().uextend(types::I32, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_load8_u_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let val = b().ins().atomic_load(types::I8, MemFlags::new(), addr);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_load16_u_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let val = b().ins().atomic_load(types::I16, MemFlags::new(), addr);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_load32_u_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let val = b().ins().atomic_load(types::I32, MemFlags::new(), addr);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

// --- Atomic stores ---

#[no_mangle]
pub extern "C" fn emit_atomic_store_i32(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    b().ins().atomic_store(MemFlags::new(), vvalue, addr);
}

#[no_mangle]
pub extern "C" fn emit_atomic_store_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    b().ins().atomic_store(MemFlags::new(), vvalue, addr);
}

#[no_mangle]
pub extern "C" fn emit_atomic_store8(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I8, vvalue);
    b().ins().atomic_store(MemFlags::new(), truncated, addr);
}

#[no_mangle]
pub extern "C" fn emit_atomic_store16(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I16, vvalue);
    b().ins().atomic_store(MemFlags::new(), truncated, addr);
}

#[no_mangle]
pub extern "C" fn emit_atomic_store8_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I8, vvalue);
    b().ins().atomic_store(MemFlags::new(), truncated, addr);
}

#[no_mangle]
pub extern "C" fn emit_atomic_store16_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I16, vvalue);
    b().ins().atomic_store(MemFlags::new(), truncated, addr);
}

#[no_mangle]
pub extern "C" fn emit_atomic_store32_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I32, vvalue);
    b().ins().atomic_store(MemFlags::new(), truncated, addr);
}

// --- Atomic RMW ---

#[no_mangle]
pub extern "C" fn emit_atomic_rmw_i32(op: u32, base: u32, wasm_addr: u32, value: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let val = b().ins().atomic_rmw(types::I32, MemFlags::new(), wasm_rmw_op(op), addr, vvalue);
    push_val(val)
}

#[no_mangle]
pub extern "C" fn emit_atomic_rmw_i64(op: u32, base: u32, wasm_addr: u32, value: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let val = b().ins().atomic_rmw(types::I64, MemFlags::new(), wasm_rmw_op(op), addr, vvalue);
    push_val(val)
}

#[no_mangle]
pub extern "C" fn emit_atomic_rmw8_u_i32(op: u32, base: u32, wasm_addr: u32, value: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I8, vvalue);
    let val = b().ins().atomic_rmw(types::I8, MemFlags::new(), wasm_rmw_op(op), addr, truncated);
    let r = b().ins().uextend(types::I32, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_rmw16_u_i32(op: u32, base: u32, wasm_addr: u32, value: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I16, vvalue);
    let val = b().ins().atomic_rmw(types::I16, MemFlags::new(), wasm_rmw_op(op), addr, truncated);
    let r = b().ins().uextend(types::I32, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_rmw8_u_i64(op: u32, base: u32, wasm_addr: u32, value: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I8, vvalue);
    let val = b().ins().atomic_rmw(types::I8, MemFlags::new(), wasm_rmw_op(op), addr, truncated);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_rmw16_u_i64(op: u32, base: u32, wasm_addr: u32, value: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I16, vvalue);
    let val = b().ins().atomic_rmw(types::I16, MemFlags::new(), wasm_rmw_op(op), addr, truncated);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_rmw32_u_i64(op: u32, base: u32, wasm_addr: u32, value: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vvalue = s().values[value as usize];
    let truncated = b().ins().ireduce(types::I32, vvalue);
    let val = b().ins().atomic_rmw(types::I32, MemFlags::new(), wasm_rmw_op(op), addr, truncated);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

// --- Atomic CAS (compare-and-swap) ---

#[no_mangle]
pub extern "C" fn emit_atomic_cas_i32(base: u32, wasm_addr: u32, expected: u32, replacement: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vexpected = s().values[expected as usize];
    let vreplacement = s().values[replacement as usize];
    let val = b().ins().atomic_cas(MemFlags::new(), addr, vexpected, vreplacement);
    push_val(val)
}

#[no_mangle]
pub extern "C" fn emit_atomic_cas_i64(base: u32, wasm_addr: u32, expected: u32, replacement: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vexpected = s().values[expected as usize];
    let vreplacement = s().values[replacement as usize];
    let val = b().ins().atomic_cas(MemFlags::new(), addr, vexpected, vreplacement);
    push_val(val)
}

#[no_mangle]
pub extern "C" fn emit_atomic_cas8_u_i32(base: u32, wasm_addr: u32, expected: u32, replacement: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vexpected = s().values[expected as usize];
    let vreplacement = s().values[replacement as usize];
    let te = b().ins().ireduce(types::I8, vexpected);
    let tr = b().ins().ireduce(types::I8, vreplacement);
    let val = b().ins().atomic_cas(MemFlags::new(), addr, te, tr);
    let r = b().ins().uextend(types::I32, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_cas16_u_i32(base: u32, wasm_addr: u32, expected: u32, replacement: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vexpected = s().values[expected as usize];
    let vreplacement = s().values[replacement as usize];
    let te = b().ins().ireduce(types::I16, vexpected);
    let tr = b().ins().ireduce(types::I16, vreplacement);
    let val = b().ins().atomic_cas(MemFlags::new(), addr, te, tr);
    let r = b().ins().uextend(types::I32, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_cas8_u_i64(base: u32, wasm_addr: u32, expected: u32, replacement: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vexpected = s().values[expected as usize];
    let vreplacement = s().values[replacement as usize];
    let te = b().ins().ireduce(types::I8, vexpected);
    let tr = b().ins().ireduce(types::I8, vreplacement);
    let val = b().ins().atomic_cas(MemFlags::new(), addr, te, tr);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_cas16_u_i64(base: u32, wasm_addr: u32, expected: u32, replacement: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vexpected = s().values[expected as usize];
    let vreplacement = s().values[replacement as usize];
    let te = b().ins().ireduce(types::I16, vexpected);
    let tr = b().ins().ireduce(types::I16, vreplacement);
    let val = b().ins().atomic_cas(MemFlags::new(), addr, te, tr);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

#[no_mangle]
pub extern "C" fn emit_atomic_cas32_u_i64(base: u32, wasm_addr: u32, expected: u32, replacement: u32, offset: i32) -> u32 {
    let addr = effective_addr(base, wasm_addr, offset);
    let vexpected = s().values[expected as usize];
    let vreplacement = s().values[replacement as usize];
    let te = b().ins().ireduce(types::I32, vexpected);
    let tr = b().ins().ireduce(types::I32, vreplacement);
    let val = b().ins().atomic_cas(MemFlags::new(), addr, te, tr);
    let r = b().ins().uextend(types::I64, val);
    push_val(r)
}

// --- Fence ---

#[no_mangle]
pub extern "C" fn emit_fence() {
    b().ins().fence();
}

// --- i64 extensions ---

/// Sign-extend i32 to i64
#[no_mangle]
pub extern "C" fn emit_sextend_i64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().sextend(types::I64, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Sign-extend i8 to i64
#[no_mangle]
pub extern "C" fn emit_sextend_8_64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let truncated = b().ins().ireduce(types::I8, va);
    let r = b().ins().sextend(types::I64, truncated);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Sign-extend i16 to i64
#[no_mangle]
pub extern "C" fn emit_sextend_16_64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let truncated = b().ins().ireduce(types::I16, va);
    let r = b().ins().sextend(types::I64, truncated);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Sign-extend i32 to i64 (for extend32_s on i64 values)
#[no_mangle]
pub extern "C" fn emit_sextend_32_64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let truncated = b().ins().ireduce(types::I32, va);
    let r = b().ins().sextend(types::I64, truncated);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- i64 comparisons ---

/// i64 eqz: compare with zero, return i32
#[no_mangle]
pub extern "C" fn emit_eqz_i64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let zero = b().ins().iconst(types::I64, 0);
    let cmp = b().ins().icmp(IntCC::Equal, va, zero);
    let r = b().ins().uextend(types::I32, cmp);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- i32 wrap i64 ---

#[no_mangle]
pub extern "C" fn emit_i32_wrap_i64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().ireduce(types::I32, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- Bitcast (for f32/f64 <-> i32/i64) ---

#[no_mangle]
pub extern "C" fn emit_bitcast_i32_to_f32(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().bitcast(types::F32, MemFlags::new(), va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_bitcast_f32_to_i32(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().bitcast(types::I32, MemFlags::new(), va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_bitcast_i64_to_f64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().bitcast(types::F64, MemFlags::new(), va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

#[no_mangle]
pub extern "C" fn emit_bitcast_f64_to_i64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().bitcast(types::I64, MemFlags::new(), va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- Jump table (for br_table) ---

/// Push a block target for br_table.
#[no_mangle]
pub extern "C" fn push_br_table_target(block_id: u32) {
    let block = s().blocks[block_id as usize];
    s().br_table_targets.push(block);
}

/// Emit br_table using Cranelift's native jump table (O(1) dispatch).
/// Pops accumulated targets, uses default_block for fallback.
#[no_mangle]
pub extern "C" fn emit_br_table(index: u32, default_block: u32) {
    let vindex = s().values[index as usize];
    let default = s().blocks[default_block as usize];
    let targets: Vec<cranelift_codegen::ir::Block> = s().br_table_targets.drain(..).collect();

    // Use dfg.block_call() to create BlockCalls (same approach as wasmtime)
    let builder = b();
    let target_calls: Vec<BlockCall> = targets
        .iter()
        .map(|&blk| builder.func.dfg.block_call(blk, &[]))
        .collect();
    let default_call = builder.func.dfg.block_call(default, &[]);

    let jt_data = cranelift_codegen::ir::JumpTableData::new(default_call, &target_calls);
    let jt = builder.create_jump_table(jt_data);
    builder.ins().br_table(vindex, jt);
}

// --- Trap ---

#[no_mangle]
pub extern "C" fn emit_trap() {
    b().ins().trap(cranelift_codegen::ir::TrapCode::user(1).unwrap());
}

// --- Block parameters ---

/// Append a typed parameter to a block. Returns the block parameter Value ID.
/// Must be called before any instructions are added to the block.
#[no_mangle]
pub extern "C" fn append_block_param(block_id: u32, wasm_type: u32) -> u32 {
    let block = s().blocks[block_id as usize];
    let ty = wasm_type_to_clif(wasm_type);
    let val = b().append_block_param(block, ty);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

// --- Control flow ---

#[no_mangle]
pub extern "C" fn emit_jump(block_id: u32) {
    let block = s().blocks[block_id as usize];
    let no_args: &[BlockArg] = &[];
    b().ins().jump(block, no_args);
}

/// Jump to block_id, passing one value argument.
#[no_mangle]
pub extern "C" fn emit_jump_with_arg(block_id: u32, val_id: u32) {
    let block = s().blocks[block_id as usize];
    let val = s().values[val_id as usize];
    b().ins().jump(block, &[BlockArg::Value(val)]);
}

/// Jump to block_id, passing accumulated call_args as block arguments.
/// Clears call_args after use.
#[no_mangle]
pub extern "C" fn emit_jump_with_args(block_id: u32) {
    let block = s().blocks[block_id as usize];
    let args: Vec<BlockArg> = s().call_args.drain(..).map(|v| BlockArg::Value(v)).collect();
    b().ins().jump(block, &args);
}

/// Conditional branch where then_block gets accumulated call_args, else_block gets none.
/// Clears call_args after use.
#[no_mangle]
pub extern "C" fn emit_brif_with_jump_args(cond: u32, then_block: u32, else_block: u32) {
    let vcond = s().values[cond as usize];
    let bt = s().blocks[then_block as usize];
    let be = s().blocks[else_block as usize];
    let args: Vec<BlockArg> = s().call_args.drain(..).map(|v| BlockArg::Value(v)).collect();
    let no_args: &[BlockArg] = &[];
    b().ins().brif(vcond, bt, &args, be, no_args);
}

#[no_mangle]
pub extern "C" fn emit_brif(cond: u32, then_block: u32, else_block: u32) {
    let vcond = s().values[cond as usize];
    let bt = s().blocks[then_block as usize];
    let be = s().blocks[else_block as usize];
    let no_args: &[BlockArg] = &[];
    b().ins().brif(vcond, bt, no_args, be, no_args);
}

/// Conditional branch with optional args per arm.
/// Use 0xFFFFFFFF (-1 as i32) for "no argument".
#[no_mangle]
pub extern "C" fn emit_brif_with_args(
    cond: u32,
    then_block: u32, then_arg: i32,
    else_block: u32, else_arg: i32,
) {
    let vcond = s().values[cond as usize];
    let bt = s().blocks[then_block as usize];
    let be = s().blocks[else_block as usize];

    let then_args: Vec<BlockArg> = if then_arg >= 0 {
        vec![BlockArg::Value(s().values[then_arg as usize])]
    } else {
        vec![]
    };
    let else_args: Vec<BlockArg> = if else_arg >= 0 {
        vec![BlockArg::Value(s().values[else_arg as usize])]
    } else {
        vec![]
    };

    b().ins().brif(vcond, bt, &then_args, be, &else_args);
}

// --- i64 memory operations ---

#[no_mangle]
pub extern "C" fn emit_load_i64(base: u32, wasm_addr: u32, offset: i32) -> u32 {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    let val = b().ins().load(types::I64, MemFlags::new(), effective, offset);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(val);
    id
}

#[no_mangle]
pub extern "C" fn emit_store_i64(base: u32, wasm_addr: u32, value: u32, offset: i32) {
    let vbase = s().values[base as usize];
    let vaddr = s().values[wasm_addr as usize];
    let vvalue = s().values[value as usize];
    let extended = b().ins().uextend(types::I64, vaddr);
    let effective = b().ins().iadd(vbase, extended);
    b().ins().store(MemFlags::new(), vvalue, effective, offset);
}

// --- Float arithmetic (polymorphic: works for both f32 and f64) ---

macro_rules! emit_float_binop {
    ($name:ident, $op:ident) => {
        #[no_mangle]
        pub extern "C" fn $name(a: u32, b_id: u32) -> u32 {
            let va = s().values[a as usize];
            let vb = s().values[b_id as usize];
            let r = b().ins().$op(va, vb);
            let session = s();
            let id = session.values.len() as u32;
            session.values.push(r);
            id
        }
    };
}

emit_float_binop!(emit_fadd, fadd);
emit_float_binop!(emit_fsub, fsub);
emit_float_binop!(emit_fmul, fmul);
emit_float_binop!(emit_fdiv, fdiv);
emit_float_binop!(emit_fmin, fmin);
emit_float_binop!(emit_fmax, fmax);
emit_float_binop!(emit_fcopysign, fcopysign);

macro_rules! emit_float_unop {
    ($name:ident, $op:ident) => {
        #[no_mangle]
        pub extern "C" fn $name(a: u32) -> u32 {
            let va = s().values[a as usize];
            let r = b().ins().$op(va);
            let session = s();
            let id = session.values.len() as u32;
            session.values.push(r);
            id
        }
    };
}

emit_float_unop!(emit_fabs, fabs);
emit_float_unop!(emit_fneg, fneg);
emit_float_unop!(emit_ceil, ceil);
emit_float_unop!(emit_floor, floor);
emit_float_unop!(emit_trunc_float, trunc);
emit_float_unop!(emit_nearest, nearest);
emit_float_unop!(emit_sqrt, sqrt);

// --- Float comparison ---

use cranelift_codegen::ir::condcodes::FloatCC;

/// cc: 0=eq, 1=ne, 2=lt, 3=gt, 4=le, 5=ge
#[no_mangle]
pub extern "C" fn emit_fcmp(cc: u32, a: u32, b_id: u32) -> u32 {
    let va = s().values[a as usize];
    let vb = s().values[b_id as usize];
    let cond = match cc {
        0 => FloatCC::Equal,
        1 => FloatCC::NotEqual,
        2 => FloatCC::LessThan,
        3 => FloatCC::GreaterThan,
        4 => FloatCC::LessThanOrEqual,
        5 => FloatCC::GreaterThanOrEqual,
        _ => panic!("Unknown fcmp condition code: {}", cc),
    };
    let cmp = b().ins().fcmp(cond, va, vb);
    let r = b().ins().uextend(types::I32, cmp);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- Float conversions ---

/// Convert float to signed int. target_type: 0=I32, 1=I64
#[no_mangle]
pub extern "C" fn emit_fcvt_to_sint(target_type: u32, a: u32) -> u32 {
    let va = s().values[a as usize];
    let ty = wasm_type_to_clif(target_type);
    let r = b().ins().fcvt_to_sint(ty, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Convert float to unsigned int. target_type: 0=I32, 1=I64
#[no_mangle]
pub extern "C" fn emit_fcvt_to_uint(target_type: u32, a: u32) -> u32 {
    let va = s().values[a as usize];
    let ty = wasm_type_to_clif(target_type);
    let r = b().ins().fcvt_to_uint(ty, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Convert float to signed int (saturating). target_type: 0=I32, 1=I64
#[no_mangle]
pub extern "C" fn emit_fcvt_to_sint_sat(target_type: u32, a: u32) -> u32 {
    let va = s().values[a as usize];
    let ty = wasm_type_to_clif(target_type);
    let r = b().ins().fcvt_to_sint_sat(ty, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Convert float to unsigned int (saturating). target_type: 0=I32, 1=I64
#[no_mangle]
pub extern "C" fn emit_fcvt_to_uint_sat(target_type: u32, a: u32) -> u32 {
    let va = s().values[a as usize];
    let ty = wasm_type_to_clif(target_type);
    let r = b().ins().fcvt_to_uint_sat(ty, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Convert signed int to float. target_type: 2=F32, 3=F64
#[no_mangle]
pub extern "C" fn emit_fcvt_from_sint(target_type: u32, a: u32) -> u32 {
    let va = s().values[a as usize];
    let ty = wasm_type_to_clif(target_type);
    let r = b().ins().fcvt_from_sint(ty, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Convert unsigned int to float. target_type: 2=F32, 3=F64
#[no_mangle]
pub extern "C" fn emit_fcvt_from_uint(target_type: u32, a: u32) -> u32 {
    let va = s().values[a as usize];
    let ty = wasm_type_to_clif(target_type);
    let r = b().ins().fcvt_from_uint(ty, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// f32 -> f64
#[no_mangle]
pub extern "C" fn emit_fpromote(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().fpromote(types::F64, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// f64 -> f32
#[no_mangle]
pub extern "C" fn emit_fdemote(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().fdemote(types::F32, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- Select ---

/// Wasm select: if cond != 0, return val_true, else val_false.
/// cond is an i32 (Wasm), convert to boolean for Cranelift's select.
#[no_mangle]
pub extern "C" fn emit_select(cond: u32, val_true: u32, val_false: u32) -> u32 {
    let vcond = s().values[cond as usize];
    let vt = s().values[val_true as usize];
    let vf = s().values[val_false as usize];
    // Convert i32 condition to boolean (i8): cond != 0
    let bool_cond = b().ins().icmp_imm(IntCC::NotEqual, vcond, 0);
    let r = b().ins().select(bool_cond, vt, vf);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- Type widening/narrowing ---

/// Zero-extend i32 to i64
#[no_mangle]
pub extern "C" fn emit_uextend_i64(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().uextend(types::I64, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

/// Truncate i64 to i32
#[no_mangle]
pub extern "C" fn emit_ireduce_i32(a: u32) -> u32 {
    let va = s().values[a as usize];
    let r = b().ins().ireduce(types::I32, va);
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(r);
    id
}

// --- SigRef builder (for call_indirect) ---

/// Start building a new signature. Call sig_add_param/sig_add_return, then end_sig.
#[no_mangle]
pub extern "C" fn begin_sig() {
    s().sig_builder = Some(Signature::new(CallConv::Tail));
}

/// Add a parameter type to the current signature being built.
#[no_mangle]
pub extern "C" fn sig_add_param(wasm_type: u32) {
    s().sig_builder.as_mut().unwrap()
        .params.push(AbiParam::new(wasm_type_to_clif(wasm_type)));
}

/// Add a return type to the current signature being built.
#[no_mangle]
pub extern "C" fn sig_add_return(wasm_type: u32) {
    s().sig_builder.as_mut().unwrap()
        .returns.push(AbiParam::new(wasm_type_to_clif(wasm_type)));
}

/// Finalize the signature and import it. Returns a sig_ref_id.
#[no_mangle]
pub extern "C" fn end_sig() -> u32 {
    let sig = s().sig_builder.take().unwrap();
    let sig_ref = s().func.import_signature(sig);
    let session = s();
    let id = session.sig_refs.len() as u32;
    session.sig_refs.push(sig_ref);
    id
}

// --- Indirect call (accumulator pattern) ---

/// Push a value as an argument for the next call_indirect.
#[no_mangle]
pub extern "C" fn push_call_arg(val_id: u32) {
    let val = s().values[val_id as usize];
    s().call_args.push(val);
}

/// Emit call_indirect with accumulated args. Returns the first result value ID.
/// Use -1 (0xFFFFFFFF) as return if the call has no return value.
#[no_mangle]
pub extern "C" fn emit_call_indirect(sig_ref_id: u32, callee: u32) -> u32 {
    let sig_ref = s().sig_refs[sig_ref_id as usize];
    let vcallee = s().values[callee as usize];
    let args: Vec<cranelift_codegen::ir::Value> = s().call_args.drain(..).collect();
    let inst = b().ins().call_indirect(sig_ref, vcallee, &args);
    let results = b().inst_results(inst);
    if results.is_empty() {
        return 0xFFFFFFFF;
    }
    let result = results[0];
    let session = s();
    let id = session.values.len() as u32;
    session.values.push(result);
    id
}

/// Emit return_call_indirect (tail call) with accumulated args.
/// This is a terminator — the callee returns directly to our caller.
#[no_mangle]
pub extern "C" fn emit_return_call_indirect(sig_ref_id: u32, callee: u32) {
    let sig_ref = s().sig_refs[sig_ref_id as usize];
    let vcallee = s().values[callee as usize];
    let args: Vec<cranelift_codegen::ir::Value> = s().call_args.drain(..).collect();
    b().ins().return_call_indirect(sig_ref, vcallee, &args);
}

// --- Return ---

#[no_mangle]
pub extern "C" fn emit_return(val_id: u32) {
    let val = s().values[val_id as usize];
    b().ins().return_(&[val]);
}

#[no_mangle]
pub extern "C" fn emit_return_void() {
    b().ins().return_(&[]);
}

/// Return multiple values from accumulated call_args.
/// Clears call_args after use.
#[no_mangle]
pub extern "C" fn emit_return_multi() {
    let args: Vec<cranelift_codegen::ir::Value> = s().call_args.drain(..).collect();
    b().ins().return_(&args);
}

// --- Compile ---

/// Finalize the builder, compile to native code, return code length.
/// Code bytes are stored internally; read with get_code_ptr/get_code_len.
#[no_mangle]
pub extern "C" fn compile() -> u32 {
    let isa = unsafe { ISA.as_ref().expect("ISA not initialized") };
    let session = s();

    // Take ownership of the builder and finalize it
    let builder = unsafe { Box::from_raw(session.builder) };
    builder.finalize();
    session.builder = std::ptr::null_mut();

    // Take ownership of the function (avoids cloning the entire IR)
    let func = std::mem::replace(&mut *session.func, Function::new());
    let mut ctx = Context::for_function(func);
    let compiled = ctx
        .compile(isa.as_ref(), &mut ControlPlane::default())
        .expect("Compilation failed");

    let code = compiled.code_buffer();

    // Clear session vecs to free memory between compilations
    session.blocks.clear();
    session.variables.clear();
    session.values.clear();
    session.sig_refs.clear();
    session.call_args.clear();
    session.jump_tables.clear();
    session.br_table_targets.clear();

    unsafe {
        COMPILED_CODE = code.to_vec();
        COMPILED_CODE.len() as u32
    }
}

#[no_mangle]
pub extern "C" fn get_code_ptr() -> *const u8 {
    unsafe { COMPILED_CODE.as_ptr() }
}

#[no_mangle]
pub extern "C" fn get_code_len() -> u32 {
    unsafe { COMPILED_CODE.len() as u32 }
}

// --- Trampoline compilation ---

#[no_mangle]
pub extern "C" fn begin_trampoline_sig() {
    unsafe { TRAMPOLINE_SIG = Some(Signature::new(CallConv::Tail)); }
}

#[no_mangle]
pub extern "C" fn trampoline_sig_add_param(wasm_type: u32) {
    unsafe {
        TRAMPOLINE_SIG.as_mut().unwrap()
            .params.push(AbiParam::new(wasm_type_to_clif(wasm_type)));
    }
}

#[no_mangle]
pub extern "C" fn trampoline_sig_add_return(wasm_type: u32) {
    unsafe {
        TRAMPOLINE_SIG.as_mut().unwrap()
            .returns.push(AbiParam::new(wasm_type_to_clif(wasm_type)));
    }
}

fn compile_trampoline(
    outer_sig: Signature,
    inner_sig: Signature,
    build_call: impl FnOnce(
        &mut FunctionBuilder,
        cranelift_codegen::ir::SigRef,
        &[cranelift_codegen::ir::Value],
    ) -> cranelift_codegen::ir::Inst,
) -> u32 {
    let isa = unsafe { ISA.as_ref().expect("ISA not initialized") };
    let mut func = Function::with_name_signature(UserFuncName::user(0, 0), outer_sig);
    let sig_ref = func.import_signature(inner_sig);

    let mut builder_ctx = FunctionBuilderContext::new();
    let mut builder = FunctionBuilder::new(&mut func, &mut builder_ctx);

    let entry = builder.create_block();
    builder.append_block_params_for_function_params(entry);
    builder.switch_to_block(entry);
    builder.seal_block(entry);

    let params = builder.block_params(entry).to_vec();
    let inst = build_call(&mut builder, sig_ref, &params);
    let results = builder.inst_results(inst).to_vec();
    builder.ins().return_(&results);
    builder.finalize();

    let mut ctx = Context::for_function(func);
    let compiled = ctx
        .compile(isa.as_ref(), &mut ControlPlane::default())
        .expect("Trampoline compilation failed");

    unsafe {
        COMPILED_CODE = compiled.code_buffer().to_vec();
        COMPILED_CODE.len() as u32
    }
}

fn copy_sig(src: &Signature, conv: CallConv) -> Signature {
    let mut sig = Signature::new(conv);
    for p in &src.params { sig.params.push(p.clone()); }
    for r in &src.returns { sig.returns.push(r.clone()); }
    sig
}

/// Compile an entry trampoline: platform ABI → Tail convention.
/// Takes (funcPtr, memBase, ctxPtr, args...) with platform ABI,
/// calls through funcPtr with Tail convention.
#[no_mangle]
pub extern "C" fn compile_entry_trampoline() -> u32 {
    let isa = unsafe { ISA.as_ref().expect("ISA not initialized") };
    let tail_sig = unsafe { TRAMPOLINE_SIG.take().expect("No trampoline sig") };

    let mut outer_sig = Signature::new(isa.default_call_conv());
    outer_sig.params.push(AbiParam::new(types::I64)); // funcPtr
    for p in &tail_sig.params { outer_sig.params.push(p.clone()); }
    for r in &tail_sig.returns { outer_sig.returns.push(r.clone()); }

    compile_trampoline(outer_sig, tail_sig, |builder, sig_ref, params| {
        builder.ins().call_indirect(sig_ref, params[0], &params[1..])
    })
}

/// Compile an import trampoline: Tail convention → platform ABI.
/// Takes (memBase, ctxPtr, args...) with Tail convention,
/// calls platform-ABI stub at baked-in address.
#[no_mangle]
pub extern "C" fn compile_import_trampoline(stub_addr_lo: u32, stub_addr_hi: u32) -> u32 {
    let isa = unsafe { ISA.as_ref().expect("ISA not initialized") };
    let tail_sig = unsafe { TRAMPOLINE_SIG.take().expect("No trampoline sig") };
    let stub_addr = ((stub_addr_hi as u64) << 32) | (stub_addr_lo as u64);

    let outer_sig = copy_sig(&tail_sig, CallConv::Tail);
    let platform_sig = copy_sig(&tail_sig, isa.default_call_conv());

    compile_trampoline(outer_sig, platform_sig, |builder, sig_ref, params| {
        let stub_ptr = builder.ins().iconst(types::I64, stub_addr as i64);
        builder.ins().call_indirect(sig_ref, stub_ptr, params)
    })
}
