package run.endive.redline.compiler.internal;

import java.util.ArrayDeque;
import java.util.Deque;

final class NativeValueStack {

    private final Deque<Integer> stack = new ArrayDeque<>();

    void push(int valueId) {
        stack.push(valueId);
    }

    int pop() {
        return stack.pop();
    }

    int peek() {
        return stack.peek();
    }

    int size() {
        return stack.size();
    }

    boolean isEmpty() {
        return stack.isEmpty();
    }

    void trimTo(int height) {
        while (stack.size() > height) {
            stack.pop();
        }
    }
}
