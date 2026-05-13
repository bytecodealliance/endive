module run.endive.compiler {
    requires transitive run.endive.runtime;
    requires transitive run.endive.wasm;
    requires org.objectweb.asm;
    requires org.objectweb.asm.commons;
    requires org.objectweb.asm.util;

    exports run.endive.compiler;
    exports run.endive.compiler.internal;
    exports run.endive.experimental.aot;
}
