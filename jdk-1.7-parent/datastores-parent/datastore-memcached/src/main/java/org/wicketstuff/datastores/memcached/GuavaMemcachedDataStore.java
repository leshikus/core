/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wicketstuff.datastores.memcached;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import net.spy.memcached.ConnectionObserver;
import net.spy.memcached.MemcachedClient;

import org.apache.wicket.pageStore.IDataStore;
import org.apache.wicket.util.lang.Args;
import org.apache.wicket.util.lang.Checks;
import org.apache.wicket.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;

/**
 * {@link org.apache.wicket.pageStore.IDataStore} that stores the pages' bytes in Memcached.
 * It uses <a href="https://code.google.com/p/guava-libraries/">Google Guava</a>'s Cache
 * as a Map with eviction functionality by last access time.
 *
 * To use it the application has to add: com.google.guava:guava:10+ as dependency in
 * the classpath.
 *
 * A useful read about the way Memcached works can be found
 * <a href="http://returnfoo.com/2012/02/memcached-memory-allocation-and-optimization-2/">here</a>.
 */
public class GuavaMemcachedDataStore implements IDataStore {

	private static final Logger LOG = LoggerFactory.getLogger(GuavaMemcachedDataStore.class);

	/**
	 * A suffix for the keys to avoid duplication of entries
	 * in Memcached entered by another process and to make
	 * it easier to find out who put the data at the server
	 */
	private static final String KEY_SUFFIX = "Wicket-Memcached-Guava";

	/**
	 * A separator used for the key construction
	 */
	private static final String SEPARATOR = "|||";

	/**
	 * The connection to the Memcached server
	 */
	private final MemcachedClient client;

	/**
	 * The configuration for the client
	 */
	private final IMemcachedSettings settings;

	/**
	 * Tracks the keys for all operations per session.
	 * Used to delete all entries for a session when invalidated.
	 *
	 * The entries in Memcached have time-to-live so they will be
	 * removed anyway at some point.
	 */
	private final Cache<String, Set<String>> keysPerSession;

	/**
	 * Constructor.
	 *
	 * Creates a MemcachedClient from the provided settings
	 *
	 * @param settings The configuration for the client
	 * @throws java.io.IOException when cannot connect to any of the provided
	 *         in IMemcachedSettings Memcached servers
	 */
	public GuavaMemcachedDataStore(IMemcachedSettings settings) throws IOException {
		this(createClient(settings), settings);
	}

	/**
	 * Constructor.
	 *
	 * @param client   The connection to Memcached
	 * @param settings The configuration for the client
	 */
	public GuavaMemcachedDataStore(MemcachedClient client, IMemcachedSettings settings) {
		this.client = Args.notNull(client, "client");
		this.settings = Args.notNull(settings, "settings");

		this.keysPerSession  = CacheBuilder.newBuilder()
				.expireAfterAccess(settings.getExpirationTime(), TimeUnit.SECONDS)
				.build();

		client.addObserver(new ConnectionObserver()
		{
			@Override
			public void connectionEstablished(SocketAddress sa, int reconnectCount)
			{
				LOG.info("Established connection to: {}, reconnect count: {}", sa, reconnectCount);
			}

			@Override
			public void connectionLost(SocketAddress sa)
			{
				LOG.warn("Lost connection to: {}", sa);
			}
		});

	}

	/**
	 * Creates MemcachedClient with the provided hostname and port
	 * in the settings
	 *
	 * @param settings  The configuration for the client
	 * @return A MemcachedClient
	 * @throws java.io.IOException when cannot connect to any of the provided Memcached servers
	 */
	public static MemcachedClient createClient(IMemcachedSettings settings) throws IOException {
		Args.notNull(settings, "settings");

		int port = settings.getPort();

		Checks.withinRangeShort(10000, 65535, port, "port");

		List<String> hostnames = settings.getServerList();
		int memcachedPort = settings.getPort();
		InetSocketAddress[] addresses = new InetSocketAddress[hostnames.size()];
		for (int i = 0; i < hostnames.size(); i++) {
			String hostname = hostnames.get(i);
			addresses[i] = new InetSocketAddress(hostname, memcachedPort);
		}

		return new MemcachedClient(addresses);
	}

	@Override
	public byte[] getData(String sessionId, int pageId) {
		String key = makeKey(sessionId, pageId);
		byte[] bytes = (byte[]) client.get(key);

		if (bytes == null) {
			removeKey(sessionId, key);
		}

		LOG.debug("Got {} for session '{}' and page id '{}'",
				new Object[] {bytes != null ? "data" : "'null'", sessionId, pageId});
		return bytes;
	}

	@Override
	public void removeData(final String sessionId, final int pageId) {
		final String key = makeKey(sessionId, pageId);
		client.delete(key);

		removeKey(sessionId, key);

		LOG.debug("Removed the data for session '{}' and page id '{}'", sessionId, pageId);
	}

	@Override
	public void removeData(String sessionId) {
		Set<String> keys = keysPerSession.getIfPresent(sessionId);
		if (keys != null) {
			for (String key : keys) {
				client.delete(key);
			}
			keysPerSession.invalidate(sessionId);
			LOG.debug("Removed the data for session '{}'", sessionId);
		}
	}

	@Override
	public void storeData(final String sessionId, final int pageId, byte[] data) {
		final String key = makeKey(sessionId, pageId);
		Set<String> keys;
		try {
			keys = keysPerSession.get(sessionId, new Callable<Set<String>>() {
				@Override
				public Set<String> call() throws Exception {
					return Sets.newSetFromMap(createEvictingMap());
				}
			});
		} catch (ExecutionException ex) {
			LOG.warn("An error occurred while creating a new set for the keys in session '{}': {}", 
					sessionId, ex.getMessage());
			keys = Sets.newSetFromMap(createEvictingMap());
			keysPerSession.put(sessionId, keys);
		}

		int expirationTime = settings.getExpirationTime();

		client.set(key, expirationTime, data);
		keys.add(key);
		LOG.debug("Stored data for session '{}' and page id '{}'", sessionId, pageId);
	}

	@Override
	public void destroy() {
		keysPerSession.invalidateAll();
		if (client != null) {
			Duration timeout = settings.getShutdownTimeout();
			LOG.info("Shutting down gracefully for {}", timeout);
			client.shutdown(timeout.getMilliseconds(), TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public boolean isReplicated() {
		return true;
	}

	@Override
	public boolean canBeAsynchronous() {
		// no need to be asynchronous
		// MemcachedClient is asynchronous itself
		return false;
	}

	/**
	 * Creates a key that is used for the lookup in Memcached.
	 * The key starts with sessionId and the pageId so
	 * {@linkplain #keysPerSession} can be sorted faster.
	 *
	 * @param sessionId The id of the http session.
	 * @param pageId    The id of the stored page
	 * @return A key that is used for the lookup in Memcached
	 */
	private String makeKey(String sessionId, int pageId) {
		return new StringBuilder()
				.append(sessionId)
				.append(SEPARATOR)
				.append(pageId)
				.append(SEPARATOR)
				.append(KEY_SUFFIX)
				.toString();
	}

	/**
	 * Removes a key from {@linkplain #keysPerSession}
	 *
	 * @param sessionId The id of the http session.
	 * @param key       The key for Memcached lookup
	 */
	private void removeKey(String sessionId, String key) {
		Set<String> keys = keysPerSession.getIfPresent(sessionId);
		if (keys != null) {
			// maybe the entry has expired
			keys.remove(key);

			if (keys.isEmpty()) {
				keysPerSession.invalidate(sessionId);
			}
		}
	}

	/**
	 * Creates a concurrent map with eviction functionality by
	 * access time.
	 *
	 * @return the evicting map
	 */
	private ConcurrentMap<String, Boolean> createEvictingMap() {
		int seconds = settings.getExpirationTime();
		Cache<String, Boolean> cache = CacheBuilder.newBuilder()
				.expireAfterAccess(seconds, TimeUnit.SECONDS)
				.build();
		return cache.asMap();
	}
}
