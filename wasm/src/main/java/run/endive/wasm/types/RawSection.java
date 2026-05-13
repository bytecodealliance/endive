package run.endive.wasm.types;

public class RawSection extends Section {
    private final byte[] contents;

    public RawSection(long id, byte[] contents) {
        super(id);
        this.contents = contents.clone();
    }

    public byte[] contents() {
        return contents.clone();
    }
}
