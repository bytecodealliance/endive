package run.endive.wasm;

import run.endive.wasm.types.Section;

@FunctionalInterface
public interface ParserListener {

    void onSection(Section section);
}
