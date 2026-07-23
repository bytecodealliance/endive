PWD := $(CURDIR)

.PHONY: clean
clean:
	rm -rf target

.PHONY: fmt
fmt:
	cargo fmt

.PHONY: build
build:
	cd ${PWD}
	cargo build --target wasm32-wasip1 --release

.PHONY: release
release:
	rm -f ${PWD}/../cranelift_bridge.wasm
	cp ${PWD}/target/wasm32-wasip1/release/cranelift_bridge.wasm ${PWD}/../cranelift_bridge.wasm

.PHONY: all
all: build release
