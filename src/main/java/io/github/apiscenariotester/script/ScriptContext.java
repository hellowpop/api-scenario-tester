package io.github.apiscenariotester.script;

import org.apache.commons.jexl3.MapContext;

/** Scripts may mutate exposed maps, but cannot replace root context bindings. */
public final class ScriptContext extends MapContext {
    public void bind(String name, Object value) { super.set(name, value); }
    @Override public void set(String name, Object value) {
        throw new IllegalArgumentException("cannot assign context binding: " + name + "; use a local var or map entry");
    }
}
