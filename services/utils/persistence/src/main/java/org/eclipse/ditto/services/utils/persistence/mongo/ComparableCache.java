/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.utils.persistence.mongo;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.eclipse.ditto.services.utils.cache.CaffeineCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Cache implementation for {@link Comparable} values which performs updates only when a value is new or greater than
 * the existing one.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values, has to extend {@link Comparable}
 */
final class ComparableCache<K, V extends Comparable<V>> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComparableCache.class);
    private static final CacheLoader<Object, Object> NULL_LOADER = k -> null;

    private final ConcurrentMap<K, V> internalCache;

    /**
     * Constructor.
     *
     * @param size the (maximum) size of this cache
     */
    public ComparableCache(final int size) {
        this(size, null);
    }

    /**
     * Constructor.
     *
     * @param size the (maximum) size of this cache
     * @param namedMetricRegistry the named {@link MetricRegistry} for cache statistics.
     */
    public ComparableCache(final int size, @Nullable final Map.Entry<String, MetricRegistry> namedMetricRegistry) {
        final Caffeine<Object, Object> caffeine = Caffeine.newBuilder().maximumSize(size);

        final CaffeineCache<K, V> caffeineCache = CaffeineCache.of(caffeine, ComparableCache.<K, V>getTypedNullLoader(),
                namedMetricRegistry);
        this.internalCache = caffeineCache.asMap();
    }

    private static <K,V> CacheLoader<K, V> getTypedNullLoader() {
        @SuppressWarnings("unchecked")
        final CacheLoader<K, V> typedNullNoader = (CacheLoader<K, V>) NULL_LOADER;
        return typedNullNoader;
    }

    /**
     * Returns the value which is associated with the specified key.
     *
     * @param key the key to get the associated value for.
     * @return the value which is associated with the specified key or {@code null}.
     * @throws NullPointerException if {@code key} is {@code null}.
     */
    public @Nullable V get(final K key) {
        requireNonNull(key);

        return internalCache.get(key);
    }

    /**
     * Associates the specified value with the specified key, if it is new or greater than the existing value.
     *
     * @param key the key to be associated with {@code newValue}.
     * @param newValue the newValue to be associated with {@code key}.
     * @throws NullPointerException if {@code key} or {@code newValue} is {@code null}.
     * @return {@code true}, if a value for {@code key} did not yet exist or {@code newValue} is greater than the
     * existing value.
     */
    public boolean updateIfNewOrGreater(final K key, final V newValue) {
        requireNonNull(key);
        requireNonNull(newValue);

        final AtomicBoolean updated = new AtomicBoolean(false);

        internalCache.compute(key, (k, existingValue) -> {
            if (existingValue == null || newValue.compareTo(existingValue) > 0) {
                updated.set(true);
                return newValue;
            }

            return existingValue;
        });

        final boolean result = updated.get();
        LOGGER.debug("Cache update with key <{}> and value <{}> returned: <{}>", key, newValue, result);
        return result;
    }


    public ConcurrentMap<K, V> asMap() {
        return internalCache;
    }
}
