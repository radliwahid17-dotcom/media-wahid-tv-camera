package tv.mediawahid.camerapro.flip;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Narrow adapter for the hash-pinned 1.0.1 binary. No reflection into Android internals. */
final class OriginalAccess {
    private OriginalAccess() { }
    static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }
    static void field(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(owner, value);
    }
    static Object call(Object owner, String name) throws ReflectiveOperationException {
        Method m = owner.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(owner);
    }
    static boolean bool(Object owner, String name) throws ReflectiveOperationException {
        return (Boolean) call(owner, name);
    }
    static Object state(Object service) throws ReflectiveOperationException { return call(service, "getSnapshot"); }

    /** Copy every original state component, preserving timer, bytes, audio and all locks. */
    static void publish(Object service, Map<Integer, Object> changes) throws ReflectiveOperationException {
        Object state = state(service);
        Object[] args = new Object[30];
        for (int i = 0; i < args.length; i++) args[i] = call(state, "component" + (i + 1));
        changes.forEach((index, value) -> args[index - 1] = value);
        Method copy = null;
        for (Method m : state.getClass().getDeclaredMethods()) {
            if (m.getName().equals("copy") && m.getParameterCount() == 30) copy = m;
        }
        if (copy == null) throw new NoSuchMethodException("CameraUiState.copy");
        Object updated = copy.invoke(state, args);
        Method publish = service.getClass().getDeclaredMethod("publish", state.getClass());
        publish.setAccessible(true);
        publish.invoke(service, updated);
    }
}
