package run.endive.wasm.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CodeSection extends Section {
    // A null entry is a body dropped by retainingFunctionBodies.
    private final List<FunctionBody> functionBodies;
    private final boolean requiresDataCount;

    private CodeSection(List<FunctionBody> functionBodies, boolean requiresDataCount) {
        super(SectionId.CODE);
        // Not List.copyOf, which rejects the nulls of dropped bodies.
        this.functionBodies = Collections.unmodifiableList(new ArrayList<>(functionBodies));
        this.requiresDataCount = requiresDataCount;
    }

    /** Returns all function bodies, with {@code null} for dropped ones. */
    public FunctionBody[] functionBodies() {
        return functionBodies.toArray(new FunctionBody[0]);
    }

    public int functionBodyCount() {
        return functionBodies.size();
    }

    /**
     * @throws IllegalStateException if the function body has been dropped
     */
    public FunctionBody getFunctionBody(int idx) {
        var body = functionBodies.get(idx);
        if (body == null) {
            throw new IllegalStateException("Function body " + idx + " has been dropped");
        }
        return body;
    }

    /**
     * Returns a copy keeping only the function bodies at {@code indices}, see {@link
     * run.endive.wasm.WasmModule#retainingFunctionBodies(Set)}.
     *
     * @param indices the code section indices of the function bodies to keep
     */
    public CodeSection retainingFunctionBodies(Set<Integer> indices) {
        var retained =
                new ArrayList<FunctionBody>(Collections.nCopies(functionBodies.size(), null));
        for (int idx : indices) {
            retained.set(idx, functionBodies.get(idx));
        }
        return new CodeSection(retained, requiresDataCount);
    }

    public boolean isRequiresDataCount() {
        return requiresDataCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<FunctionBody> functionBodies = new ArrayList<>();
        private boolean requiresDataCount;

        private Builder() {}

        /**
         * Add a function body to this section.
         *
         * @param functionBody the function body to add to this section (must not be {@code null})
         * @return the Builder
         */
        public Builder addFunctionBody(FunctionBody functionBody) {
            Objects.requireNonNull(functionBody, "functionBody");
            functionBodies.add(functionBody);
            return this;
        }

        public Builder setRequiresDataCount(boolean requiresDataCount) {
            this.requiresDataCount = requiresDataCount;
            return this;
        }

        public CodeSection build() {
            return new CodeSection(functionBodies, requiresDataCount);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CodeSection)) {
            return false;
        }
        CodeSection that = (CodeSection) o;
        return requiresDataCount == that.requiresDataCount
                && Objects.equals(functionBodies, that.functionBodies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(functionBodies, requiresDataCount);
    }
}
