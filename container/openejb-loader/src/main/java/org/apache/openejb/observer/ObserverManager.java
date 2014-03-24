/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.observer;

import org.apache.openejb.observer.event.AfterEvent;
import org.apache.openejb.observer.event.BeforeEvent;
import org.apache.openejb.observer.event.ObserverAdded;
import org.apache.openejb.observer.event.ObserverFailed;
import org.apache.openejb.observer.event.ObserverRemoved;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ObserverManager {

    private static final ThreadLocal<Set<Invocation>> seen = new ThreadLocal<Set<Invocation>>() {
        @Override
        protected Set<Invocation> initialValue() {
            return new HashSet<Invocation>();
        }
    };

    private static final Logger LOGGER = Logger.getLogger(ObserverManager.class.getName());
    private final Set<Observer> observers = new LinkedHashSet<Observer>();
    private final Map<Class, Invocation> methods = new ConcurrentHashMap<Class, Invocation>();

    public boolean addObserver(Object observer) {
        if (observer == null) throw new IllegalArgumentException("observer cannot be null");

        try {
            if (observers.add(new Observer(observer))) {
                methods.clear();
                fireEvent(new ObserverAdded(observer));
                return true;
            } else {
                return false;
            }
        } catch (NotAnObserverException naoe) {
            return false;
        }
    }

    public boolean removeObserver(final Object observer) {
        if (observer == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        try {
            if (observers.remove(new Observer(observer))) {
                methods.clear();
                fireEvent(new ObserverRemoved(observer));
                return true;
            } else {
                return false;
            }
        } catch (NotAnObserverException naoe) {
            return false;
        }
    }

    public <E> E fireEvent(E event) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }

        try {
            return doFire(event);
        } finally {
            seen.remove();
        }
    }

    private <E> E doFire(E event) {
        final Class<?> type = event.getClass();

        final Invocation invocation = getInvocation(type);

        invocation.invoke(event);

        return event;
    }

    private Invocation getInvocation(Class<?> type) {
        {
            final Invocation invocation = methods.get(type);
            if (invocation != null) {
                return invocation;
            }
        }

        final Invocation invocation = buildInvocation(type);
        methods.put(type, invocation);
        return invocation;
    }

    private static enum Phase {
        BEFORE,
        INVOKE,
        AFTER
    }

    private Invocation buildInvocation(final Class<?> type) {
        final Invocation before = buildInvocation(Phase.BEFORE, type);
        final Invocation after = buildInvocation(Phase.AFTER, type);
        final Invocation invoke = buildInvocation(Phase.INVOKE, type);

        if (IGNORE == before && IGNORE == after) {

            return invoke;

        } else {

            return new BeforeAndAfterInvocationSet(before, invoke, after);
        }
    }

    private Invocation buildInvocation(final Phase phase, final Class<?> type) {

        final InvocationList list = new InvocationList();

        for (final Observer observer : observers) {

            final Invocation method = observer.get(phase, type);

            if (method != null && method != IGNORE) {

                list.add(method);

            }
        }

        if (list.getInvocations().size() == 0) {

            return IGNORE;

        } else if (list.getInvocations().size() == 1) {

            return list.getInvocations().get(0);

        } else {

            return list;
        }
    }

    /**
     * @version $Rev$ $Date$
     */
    public class Observer {

        private final Map<Class, Invocation> before = new ConcurrentHashMap<Class, Invocation>();
        private final Map<Class, Invocation> methods = new ConcurrentHashMap<Class, Invocation>();
        private final Map<Class, Invocation> after = new ConcurrentHashMap<Class, Invocation>();
        private final Object observer;

        public Observer(final Object observer) {
            if (observer == null) {
                throw new IllegalArgumentException("observer cannot be null");
            }

            final Set<Method> methods = new HashSet<Method>();
            methods.addAll(Arrays.asList(observer.getClass().getMethods()));
            methods.addAll(Arrays.asList(observer.getClass().getDeclaredMethods()));

            this.observer = observer;
            for (final Method method : methods) {
                if (!isObserver(method)) {
                    continue;
                }

                if (method.getParameterTypes().length > 1) {
                    throw new IllegalArgumentException("@Observes method must have only 1 parameter: " + method.toString());
                }

                if (Modifier.isAbstract(method.getModifiers())) {
                    throw new IllegalArgumentException("@Observes method must not be abstract: " + method.toString());
                }

                if (Modifier.isStatic(method.getModifiers())) {
                    throw new IllegalArgumentException("@Observes method must not be static: " + method.toString());
                }

                if (!Modifier.isPublic(method.getModifiers())) {
                    throw new IllegalArgumentException("@Observes method must be public: " + method.toString());
                }

                final Class<?> type = method.getParameterTypes()[0];

                if (AfterEvent.class.equals(type)) {

                    final Class parameterClass = getParameterClass(method);
                    this.after.put(parameterClass, new AfterInvocation(method, observer));

                } else if (BeforeEvent.class.equals(type)) {

                    final Class parameterClass = getParameterClass(method);
                    this.before.put(parameterClass, new BeforeInvocation(method, observer));

                } else {

                    validate(method, type);
                    this.methods.put(type, new MethodInvocation(method, observer));

                }
            }

            if (methods.size() == 0 && after.size() == 0 && before.size() == 0) {
                throw new NotAnObserverException("Object has no @Observes methods. For example: public void observe(@Observes RetryConditionAdded event){...}");
            }
        }

        private Class getParameterClass(final Method method) {

            final Type[] genericParameterTypes = method.getGenericParameterTypes();

            final Type generic = genericParameterTypes[0];

            if (!(generic instanceof ParameterizedType)) {
                final Class<?> event = method.getParameterTypes()[0];
                throw new IllegalArgumentException("@Observes " + event.getSimpleName() + " missing generic type: " + method.toString());
            }

            final ParameterizedType parameterized = ParameterizedType.class.cast(generic);

            final Type type = parameterized.getActualTypeArguments()[0];

            final Class clazz;

            if (type instanceof Class) {

                clazz = Class.class.cast(type);

            } else if (type instanceof WildcardType) {

                clazz = Object.class;

            } else {

                final Class<?> event = method.getParameterTypes()[0];
                throw new IllegalArgumentException("@Observes " + event.getSimpleName() + " unsupported generic type: " + type.getClass().getSimpleName() + "  " + method.toString());
            }

            validate(method, clazz);

            return clazz;
        }

        private void validate(final Method method, final Class<?> type) {
            if (type.isAnnotation()) {
                throw new IllegalArgumentException("@Observes method parameter must be a concrete class (not an annotation): " + method.toString());
            }

            if (type.isInterface()) {
                throw new IllegalArgumentException("@Observes method parameter must be a concrete class (not an interface): " + method.toString());
            }

            if (type.isArray()) {
                throw new IllegalArgumentException("@Observes method parameter must be a concrete class (not an array): " + method.toString());
            }

            if (type.isPrimitive()) {
                throw new IllegalArgumentException("@Observes method parameter must be a concrete class (not a primitive): " + method.toString());
            }
        }

        private Map<Class, Invocation> map(final Phase event) {
            switch (event) {
                case AFTER:
                    return after;
                case BEFORE:
                    return before;
                case INVOKE:
                    return methods;
                default:
                    throw new IllegalStateException("Unknown Event style " + event);
            }
        }

        public Invocation get(final Phase event, final Class eventType) {
            return get(map(event), eventType);
        }

        public Invocation getAfter(final Class eventType) {
            return get(after, eventType);
        }

        public Invocation getBefore(final Class eventType) {
            return get(before, eventType);
        }

        private Invocation get(final Map<Class, Invocation> map, final Class eventType) {
            if (eventType == null) return IGNORE;

            final Invocation method = map.get(eventType);

            if (method != null) return method;

            return get(map, eventType.getSuperclass());
        }

        private boolean isObserver(final Method method) {
            for (final Annotation[] annotations : method.getParameterAnnotations()) {
                for (final Annotation annotation : annotations) {
                    if (annotation.annotationType().equals(Observes.class)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final Observer observer1 = (Observer) o;

            return observer.equals(observer1.observer);
        }

        @Override
        public int hashCode() {
            return observer.hashCode();
        }
    }

    public interface Invocation {
        void invoke(Object event);
    }


    private static final Invocation IGNORE = new Invocation() {
        @Override
        public void invoke(Object event) {
        }

        @Override
        public String toString() {
            return "IGNORE";
        }
    };


    public class MethodInvocation implements Invocation {
        private final Method method;
        private final Object observer;

        public MethodInvocation(Method method, Object observer) {
            this.method = method;
            this.observer = observer;
        }

        @Override
        public void invoke(final Object event) {
            try {
                method.invoke(observer, event);
            } catch (InvocationTargetException e) {
                if (!seen.get().add(this)) return;

                final Throwable t = e.getTargetException() == null ? e : e.getTargetException();

                if (!(event instanceof ObserverFailed)) {
                    doFire(new ObserverFailed(observer, method, event, t));
                }

                if (t instanceof InvocationTargetException && t.getCause() != null) {
                    LOGGER.log(Level.SEVERE, "error invoking " + observer, t.getCause());
                } else {
                    LOGGER.log(Level.SEVERE, "error invoking " + observer, t);
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public String toString() {
            return method.toString();
        }
    }

    private static class Stack {
        private final int[] seen = new int[10];
        private int i = 0;

        public boolean seen(Invocation invocation) {
            int code = invocation.hashCode();

            for (int j = 0; j < seen.length; j++) {
                if (seen[j] == code) return true;
            }

            seen[i++] = code;

            if (i >= seen.length) {
                i = 0;
            }

            return false;
        }
    }

    private class AfterInvocation extends MethodInvocation {

        private AfterInvocation(Method method, Object observer) {
            super(method, observer);
        }

        @Override
        public void invoke(final Object event) {
            super.invoke(new AfterEvent() {
                @Override
                public Object getEvent() {
                    return event;
                }
            });
        }
    }

    private class BeforeInvocation extends MethodInvocation {

        private BeforeInvocation(Method method, Object observer) {
            super(method, observer);
        }

        @Override
        public void invoke(final Object event) {
            super.invoke(new BeforeEvent() {
                @Override
                public Object getEvent() {
                    return event;
                }
            });
        }
    }

    private static class BeforeAndAfterInvocationSet implements Invocation {

        private final Invocation before;
        private final Invocation invoke;
        private final Invocation after;

        private BeforeAndAfterInvocationSet(Invocation before, Invocation invoke, Invocation after) {
            this.before = before;
            this.invoke = invoke;
            this.after = after;
        }

        @Override
        public void invoke(Object event) {
            before.invoke(event);
            invoke.invoke(event);
            after.invoke(event);
        }
    }

    public static class InvocationList implements Invocation {

        private final List<Invocation> invocations = new LinkedList<Invocation>();

        public boolean add(Invocation invocation) {
            return invocations.add(invocation);
        }

        public List<Invocation> getInvocations() {
            return invocations;
        }

        @Override
        public void invoke(Object event) {
            for (Invocation invocation : invocations) {
                invocation.invoke(event);
            }
        }
    }

    public static class NotAnObserverException extends IllegalArgumentException {
        public NotAnObserverException(String s) {
            super(s);
        }
    }
}
