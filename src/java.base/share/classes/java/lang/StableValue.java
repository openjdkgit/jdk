/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang;

import jdk.internal.access.SharedSecrets;
import jdk.internal.javac.PreviewFeature;
import jdk.internal.lang.stable.StableValueImpl;
import jdk.internal.lang.stable.StableValueUtil;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * A thin, atomic, non-blocking, thread-safe, set-at-most-once, stable value holder
 * eligible for certain JVM optimizations if set to a value.
 * <p>
 * A stable value is said to be monotonic because the state of a stable value can only go
 * from <em>unset</em> to <em>set</em> and consequently, a value can only be set
 * at most once.
 * <p>
 * StableValue is mainly intended to be a member of a holding class and is usually neither
 * exposed directly via accessors nor passed as a method parameter.
 *
 * <h2 id="factories">Factories</h2>
 * <p>
 * To create a new fresh (unset) StableValue, use the
 * {@linkplain StableValue#newInstance() StableValue::newInstance} factory.
 * <p>
 * This class also contains a number of convenience methods for creating constructs
 * involving stable values:
 * <ul>
 *     <li>
 * A <em>caching</em> (also called "memoized") Supplier, where a given {@code original}
 * Supplier is guaranteed to be successfully invoked at most once even in a multithreaded
 * environment, can be created like this:
 * {@snippet lang = java :
 *     Supplier<T> cache = StableValue.newCachingSupplier(original);
 * }
 * The caching supplier can also be computed by a fresh background thread if a
 * thread factory is provided as a second parameter as shown here:
 * {@snippet lang = java :
 *     Supplier<T> cache = StableValue.newCachingSupplier(original, Thread.ofVirtual().factory());
 * }
 *     </li>
 *
 *     <li>
 * A caching (also called "memoized") IntFunction, for the allowed given {@code size}
 * input values {@code [0, size)} and where the given {@code original} IntFunction is
 * guaranteed to be successfully invoked at most once per inout index even in a
 * multithreaded environment, can be created like this:
 * {@snippet lang = java:
 *     IntFunction<R> cache = StableValue.newCachingIntFunction(size, original);
 *}
 * Just like a caching supplier, a thread factory can be provided as a second parameter
 * allowing all the values for the allowed input values to be computed by distinct
 * background threads.
 *     </li>
 *
 *     <li>
 * A caching (also called "memoized") Function, for the given set of allowed {@code inputs}
 * and where the given {@code original} function is guaranteed to be successfully invoked
 * at most once per input value even in a multithreaded environment, can be created like
 * this:
 * {@snippet lang = java :
 *    Function<T, R> cache = StableValue.newCachingFunction(inputs, original);
 * }
 * Just like a caching supplier, a thread factory can be provided as a second parameter
 * allowing all the values for the allowed input values to be computed by distinct
 * background threads.
 *     </li>
 *
 *     <li>
 * A lazy List of stable elements with a given {@code size} and given {@code mapper} can
 * be created the following way:
 * {@snippet lang = java :
 *     List<E> lazyList = StableValue.lazyList(size, mapper);
 * }
 * The list can be used to model stable one-dimensional arrays. If two- or more
 * dimensional arrays are to be modeled, a List of List of ... of E can be used.
 *     </li>
 *
 *     <li>
 * A lazy Map with a given set of {@code keys} and given {@code mapper} associated with
 * stable values can be created like this:
 * {@snippet lang = java :
 *     Map<K, V> lazyMap = StableValue.lazyMap(keys, mapper);
 * }
 *     </li>
 *
 * </ul>
 * <p>
 * The constructs above are eligible for similar JVM optimizations as StableValue
 * instances.
 *
 * <h2 id="memory-consistency">Memory Consistency Properties</h2>
 * Actions on a presumptive holder value in a thread prior to calling a method that <i>sets</i>
 * the holder value are seen by any other thread that <i>observes</i> a set holder value.
 *
 * More generally, the action of attempting to interact (i.e. via load or store operations)
 * with a StableValue's holder value (e.g. via {@link StableValue#trySet} or
 * {@link StableValue#orElseThrow()}) forms a
 * <a href="{@docRoot}/java.base/java/util/concurrent/package-summary.html#MemoryVisibility"><i>happens-before</i></a>
 * relation between any other attempt to interact with the StableValue's holder value.
 *
 * <h2 id="nullability">Nullability</h2>
 * Except for a StableValue's holder value itself, all method parameters must be
 * <em>non-null</em> or a {@link NullPointerException} will be thrown.
 *
 * <a id="identity"></a>
 * Implementations of this interface can be
 * <a href="{@docRoot}/java.base/java/lang/doc-files/ValueBased.html">value-based</a>
 * classes; programmers should treat instances that are
 * {@linkplain Object#equals(Object) equal} as interchangeable and should not
 * use instances for synchronization, or unpredictable behavior may
 * occur. For example, in a future release, synchronization may fail.
 * The {@code equals} method should be used for comparisons.
 *
 * @param <T> type of the holder value
 *
 * @since 24
 */
@PreviewFeature(feature = PreviewFeature.Feature.STABLE_VALUES)
public sealed interface StableValue<T>
        permits StableValueImpl {

    // Principal methods

    /**
     * {@return {@code true} if the holder value was set to the provided {@code value},
     * otherwise returns {@code false}}
     * <p>
     * When this method returns, a holder value is always set.
     *
     * @param value to set (nullable)
     */
    boolean trySet(T value);

    /**
     * {@return the set holder value (nullable) if set, otherwise return the
     * {@code other} value}
     *
     * @param other to return if the stable holder value is not set
     */
    T orElse(T other);

    /**
     * {@return the set holder value if set, otherwise throws {@code NoSuchElementException}}
     *
     * @throws NoSuchElementException if no value is set
     */
    T orElseThrow();

    /**
     * {@return {@code true} if a holder value is set, {@code false} otherwise}
     */
    boolean isSet();

    // Convenience methods

    /**
     * Sets the holder value to the provided {@code value}, or, if already set,
     * throws {@link IllegalStateException}}
     * <p>
     * When this method returns (or throws an Exception), a holder value is always set.
     *
     * @param value to set (nullable)
     * @throws IllegalStateException if a holder value is already set
     */
    default void setOrThrow(T value) {
        if (!trySet(value)) {
            throw new IllegalStateException("Cannot set the holder value to " + value +
                    " because a holder value is alredy set: " + this);
        }
    }

    // Factories

    /**
     * {@return a fresh stable value with an unset holder value}
     *
     * @param <T> type of the holder value
     */
    static <T> StableValue<T> newInstance() {
        return StableValueUtil.newInstance();
    }

    /**
     * {@return a new caching, thread-safe, stable, lazily computed
     * {@linkplain Supplier supplier} that records the value of the provided
     * {@code original} supplier upon being first accessed via
     * {@linkplain Supplier#get() Supplier::get}}
     * <p>
     * The provided {@code original} supplier is guaranteed to be successfully invoked
     * at most once even in a multi-threaded environment. Competing threads invoking the
     * {@linkplain Supplier#get() Supplier::get} method when a value is already under
     * computation will block until a value is computed or an exception is thrown by the
     * computing thread.
     * <p>
     * If the {@code original} Supplier invokes the returned Supplier recursively,
     * a StackOverflowError will be thrown when the returned
     * Supplier's {@linkplain Supplier#get() Supplier::get} method is invoked.
     * <p>
     * If the provided {@code original} supplier throws an exception, it is relayed
     * to the initial caller.
     *
     * @param original supplier used to compute a memoized value
     * @param <T>      the type of results supplied by the returned supplier
     */
    static <T> Supplier<T> newCachingSupplier(Supplier<? extends T> original) {
        Objects.requireNonNull(original);
        return StableValueUtil.newCachingSupplier(original, null);
    }

    /**
     * {@return a new caching, thread-safe, stable, lazily computed
     * {@linkplain Supplier supplier} that records the value of the provided
     * {@code original} supplier upon being first accessed via
     * {@linkplain Supplier#get() Supplier::get}, or via a background thread
     * created from the provided {@code factory}}
     * <p>
     * The provided {@code original} supplier is guaranteed to be successfully invoked
     * at most once even in a multi-threaded environment. Competing threads invoking the
     * {@linkplain Supplier#get() Supplier::get} method when a value is already under
     * computation will block until a value is computed or an exception is thrown by the
     * computing thread.
     * <p>
     * If the {@code original} Supplier invokes the returned Supplier recursively,
     * a StackOverflowError will be thrown when the returned
     * Supplier's {@linkplain Supplier#get() Supplier::get} method is invoked.
     * <p>
     * If the provided {@code original} supplier throws an exception, it is relayed
     * to the initial caller. If the memoized supplier is computed by a background thread,
     * exceptions from the provided {@code original} supplier will be relayed to the
     * background thread's {@linkplain Thread#getUncaughtExceptionHandler() uncaught
     * exception handler}.
     *
     * @param original supplier used to compute a memoized value
     * @param factory  a factory that will be used to create a background thread that will
     *                 attempt to compute the memoized value.
     * @param <T>      the type of results supplied by the returned supplier
     */
    static <T> Supplier<T> newCachingSupplier(Supplier<? extends T> original,
                                              ThreadFactory factory) {
        Objects.requireNonNull(original);
        Objects.requireNonNull(factory);
        return StableValueUtil.newCachingSupplier(original, factory);
    }

    /**
     * {@return a new caching, thread-safe, stable, lazily computed
     * {@link IntFunction } that, for each allowed input, records the values of the
     * provided {@code original} IntFunction upon being first accessed via
     * {@linkplain IntFunction#apply(int) IntFunction::apply}}
     * <p>
     * The provided {@code original} IntFunction is guaranteed to be successfully invoked
     * at most once per allowed input, even in a multi-threaded environment. Competing
     * threads invoking the {@linkplain IntFunction#apply(int) IntFunction::apply} method
     * when a value is already under computation will block until a value is computed or
     * an exception is thrown by the computing thread.
     * <p>
     * If the {@code original} IntFunction invokes the returned IntFunction recursively
     * for a particular input value, a StackOverflowError will be thrown when the returned
     * IntFunction's {@linkplain IntFunction#apply(int) IntFunction::apply} method is
     * invoked.
     * <p>
     * If the provided {@code original} IntFunction throws an exception, it is relayed
     * to the initial caller.
     *
     * @param size     the size of the allowed inputs in {@code [0, size)}
     * @param original IntFunction used to compute a memoized value
     * @param <R>      the type of results delivered by the returned IntFunction
     */
    static <R> IntFunction<R> newCachingIntFunction(int size,
                                                    IntFunction<? extends R> original) {
        if (size < 0) {
            throw new IllegalArgumentException();
        }
        Objects.requireNonNull(original);
        return StableValueUtil.newCachingIntFunction(size, original, null);
    }

    /**
     * {@return a new caching, thread-safe, stable, lazily computed
     * {@link IntFunction } that, for each allowed input, records the values of the
     * provided {@code original} IntFunction upon being first accessed via
     * {@linkplain IntFunction#apply(int) IntFunction::apply}, or via background
     * threads created from the provided {@code factory} (if non-null)}
     * <p>
     * The provided {@code original} IntFunction is guaranteed to be successfully invoked
     * at most once per allowed input, even in a multi-threaded environment. Competing
     * threads invoking the {@linkplain IntFunction#apply(int) IntFunction::apply} method
     * when a value is already under computation will block until a value is computed or
     * an exception is thrown by the computing thread.
     * <p>
     * If the {@code original} IntFunction invokes the returned IntFunction recursively
     * for a particular input value, a StackOverflowError will be thrown when the returned
     * IntFunction's {@linkplain IntFunction#apply(int) IntFunction::apply} method is
     * invoked.
     * <p>
     * If the provided {@code original} IntFunction throws an exception, it is relayed
     * to the initial caller. If the memoized IntFunction is computed by a background
     * thread, exceptions from the provided {@code original} IntFunction will be relayed
     * to the background thread's {@linkplain Thread#getUncaughtExceptionHandler()
     * uncaught exception handler}.
     * <p>
     * The order in which background threads are started is unspecified.
     *
     * @param size     the size of the allowed inputs in {@code [0, size)}
     * @param original IntFunction used to compute a memoized value
     * @param factory  a factory that will be used to create {@code size} background
     *                 threads that will attempt to compute all the memoized values.
     * @param <R>      the type of results delivered by the returned IntFunction
     */
    static <R> IntFunction<R> newCachingIntFunction(int size,
                                                    IntFunction<? extends R> original,
                                                    ThreadFactory factory) {
        if (size < 0) {
            throw new IllegalArgumentException();
        }
        Objects.requireNonNull(original);
        Objects.requireNonNull(factory);
        return StableValueUtil.newCachingIntFunction(size, original, factory);
    }

    /**
     * {@return a new caching, thread-safe, stable, lazily computed {@link Function}
     * that, for each allowed input in the given set of {@code inputs}, records the
     * values of the provided {@code original} Function upon being first accessed via
     * {@linkplain Function#apply(Object) Function::apply}, or optionally via background
     * threads created from the provided {@code factory} (if non-null)}
     * <p>
     * The provided {@code original} Function is guaranteed to be successfully invoked
     * at most once per allowed input, even in a multi-threaded environment. Competing
     * threads invoking the {@linkplain Function#apply(Object) Function::apply} method
     * when a value is already under computation will block until a value is computed or
     * an exception is thrown by the computing thread.
     * <p>
     * If the {@code original} Function invokes the returned Function recursively
     * for a particular input value, a StackOverflowError will be thrown when the returned
     * Function's {@linkplain Function#apply(Object) Function::apply} method is invoked.
     * <p>
     * If the provided {@code original} Function throws an exception, it is relayed
     * to the initial caller. If the memoized Function is computed by a background
     * thread, exceptions from the provided {@code original} Function will be relayed to
     * the background thread's {@linkplain Thread#getUncaughtExceptionHandler() uncaught
     * exception handler}.
     * <p>
     * The order in which background threads are started is unspecified.
     *
     * @param inputs   the set of allowed input values
     * @param original Function used to compute a memoized value
     * @param <T>      the type of the input to the returned Function
     * @param <R>      the type of results delivered by the returned Function
     */
    static <T, R> Function<T, R> newCachingFunction(Set<? extends T> inputs,
                                                    Function<? super T, ? extends R> original) {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(original);
        return StableValueUtil.newCachingFunction(inputs, original, null);
    }

    /**
     * {@return a new caching, thread-safe, stable, lazily computed {@link Function}
     * that, for each allowed input in the given set of {@code inputs}, records the
     * values of the provided {@code original} Function upon being first accessed via
     * {@linkplain Function#apply(Object) Function::apply}, or via background
     * threads created from the provided {@code factory} (if non-null)}
     * <p>
     * The provided {@code original} Function is guaranteed to be successfully invoked
     * at most once per allowed input, even in a multi-threaded environment. Competing
     * threads invoking the {@linkplain Function#apply(Object) Function::apply} method
     * when a value is already under computation will block until a value is computed or
     * an exception is thrown by the computing thread.
     * <p>
     * If the {@code original} Function invokes the returned Function recursively
     * for a particular input value, a StackOverflowError will be thrown when the returned
     * Function's {@linkplain Function#apply(Object) Function::apply} method is invoked.
     * <p>
     * If the provided {@code original} Function throws an exception, it is relayed
     * to the initial caller. If the memoized Function is computed by a background
     * thread, exceptions from the provided {@code original} Function will be relayed to
     * the background thread's {@linkplain Thread#getUncaughtExceptionHandler() uncaught
     * exception handler}.
     * <p>
     * The order in which background threads are started is unspecified.
     *
     * @param inputs   the set of allowed input values
     * @param original Function used to compute a memoized value
     * @param factory  a factory that will be used to create {@code size} background
     *                 threads that will attempt to compute the memoized values.
     * @param <T>      the type of the input to the returned Function
     * @param <R>      the type of results delivered by the returned Function
     */
    static <T, R> Function<T, R> newCachingFunction(Set<? extends T> inputs,
                                                    Function<? super T, ? extends R> original,
                                                    ThreadFactory factory) {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(original);
        Objects.requireNonNull(factory);
        return StableValueUtil.newCachingFunction(inputs, original, factory);
    }

    /**
     * {@return a lazy, shallowly immutable, stable List of the provided {@code size}
     * where the individual elements of the list are lazily computed via the provided
     * {@code mapper} whenever an element is first accessed (directly or indirectly),
     * for example via {@linkplain List#get(int) List::get}}
     * <p>
     * The provided {@code mapper} IntFunction is guaranteed to be successfully invoked
     * at most once per list index, even in a multi-threaded environment. Competing
     * threads accessing an element already under computation will block until an element
     * is computed or an exception is thrown by the computing thread.
     * <p>
     * If the {@code mapper} IntFunction invokes the returned IntFunction recursively
     * for a particular index, a StackOverflowError will be thrown when the returned
     * List's {@linkplain List#get(int) List::get} method is invoked.
     * <p>
     * If the provided {@code mapper} IntFunction throws an exception, it is relayed
     * to the initial caller and no element is computed.
     * <p>
     * The returned List is not {@link Serializable}
     *
     * @param size   the size of the returned list
     * @param mapper to invoke whenever an element is first accessed (may return null)
     * @param <T>    the {@code StableValue}s' element type
     */
    static <T> List<T> lazyList(int size, IntFunction<? extends T> mapper) {
        if (size < 0) {
            throw new IllegalArgumentException();
        }
        Objects.requireNonNull(mapper);
        return SharedSecrets.getJavaUtilCollectionAccess().lazyList(size, mapper);
    }

    /**
     * {@return a lazy, shallowly immutable, stable Map of the provided {@code keys}
     * where the associated values of the maps are lazily computed vio the provided
     * {@code mapper} whenever a value is first accessed (directly or indirectly), for
     * example via {@linkplain Map#get(Object) Map::get}}
     * <p>
     * The provided {@code mapper} Function is guaranteed to be successfully invoked
     * at most once per key, even in a multi-threaded environment. Competing
     * threads accessing an associated value already under computation will block until
     * an associated value is computed or an exception is thrown by the computing thread.
     * <p>
     * If the {@code mapper} Function invokes the returned Map recursively
     * for a particular key, a StackOverflowError will be thrown when the returned
     * Map's {@linkplain Map#get(Object) Map::get}} method is invoked.
     * <p>
     * If the provided {@code mapper} Function throws an exception, it is relayed
     * to the initial caller and no value is computed.
     * <p>
     * The returned Map is not {@link Serializable}
     *
     * @param keys   the keys in the returned map
     * @param mapper to invoke whenever an associated value is first accessed
     *                (may return null)
     * @param <K>    the type of keys maintained by the returned map
     * @param <V>    the type of mapped values
     */
    static <K, V> Map<K, V> lazyMap(Set<K> keys, Function<? super K, ? extends V> mapper) {
        Objects.requireNonNull(keys);
        Objects.requireNonNull(mapper);
        return SharedSecrets.getJavaUtilCollectionAccess().lazyMap(keys, mapper);
    }

}
