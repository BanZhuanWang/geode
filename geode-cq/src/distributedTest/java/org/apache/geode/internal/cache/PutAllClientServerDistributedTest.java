/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.geode.cache.RegionShortcut.LOCAL;
import static org.apache.geode.cache.RegionShortcut.PARTITION;
import static org.apache.geode.cache.RegionShortcut.PARTITION_PERSISTENT;
import static org.apache.geode.cache.RegionShortcut.REPLICATE;
import static org.apache.geode.cache.RegionShortcut.REPLICATE_PERSISTENT;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.MCAST_PORT;
import static org.apache.geode.test.awaitility.GeodeAwaitility.await;
import static org.apache.geode.test.dunit.DistributedTestUtils.getLocatorPort;
import static org.apache.geode.test.dunit.DistributedTestUtils.getLocators;
import static org.apache.geode.test.dunit.IgnoredException.addIgnoredException;
import static org.apache.geode.test.dunit.VM.getHostName;
import static org.apache.geode.test.dunit.VM.getVM;
import static org.apache.geode.test.dunit.VM.toArray;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import org.apache.geode.DataSerializable;
import org.apache.geode.DataSerializer;
import org.apache.geode.cache.CacheWriterException;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.DiskStore;
import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.Operation;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.Region.Entry;
import org.apache.geode.cache.RegionDestroyedException;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.client.Pool;
import org.apache.geode.cache.client.PoolFactory;
import org.apache.geode.cache.client.PoolManager;
import org.apache.geode.cache.client.ServerConnectivityException;
import org.apache.geode.cache.client.ServerOperationException;
import org.apache.geode.cache.persistence.PartitionOfflineException;
import org.apache.geode.cache.query.CqAttributes;
import org.apache.geode.cache.query.CqAttributesFactory;
import org.apache.geode.cache.query.CqEvent;
import org.apache.geode.cache.query.CqListener;
import org.apache.geode.cache.query.CqQuery;
import org.apache.geode.cache.query.SelectResults;
import org.apache.geode.cache.query.Struct;
import org.apache.geode.cache.server.CacheServer;
import org.apache.geode.cache.util.CacheListenerAdapter;
import org.apache.geode.cache.util.CacheWriterAdapter;
import org.apache.geode.distributed.DistributedSystemDisconnectedException;
import org.apache.geode.internal.cache.tier.sockets.VersionedObjectList;
import org.apache.geode.internal.cache.versions.VersionTag;
import org.apache.geode.test.awaitility.GeodeAwaitility;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.cache.CacheTestCase;
import org.apache.geode.test.dunit.rules.SharedErrorCollector;
import org.apache.geode.test.junit.categories.ClientServerTest;
import org.apache.geode.test.junit.categories.ClientSubscriptionTest;
import org.apache.geode.test.junit.rules.serializable.SerializableTestName;

/**
 * Tests putAll for c/s. Also tests removeAll
 *
 * @since GemFire 5.0.23
 */
@Category({ClientServerTest.class, ClientSubscriptionTest.class})
@RunWith(JUnitParamsRunner.class)
@SuppressWarnings("serial")
public class PutAllClientServerDistributedTest extends CacheTestCase {

  private static final long TIMEOUT_MILLIS = GeodeAwaitility.getTimeout().getValueInMS();

  private static final int ONE_HUNDRED = 100;
  private static final int ONE_THOUSAND = 1000;

  private static final CacheListenerAction<Integer> EMPTY_INTEGER_ACTION =
      new CacheListenerAction<>(null, emptyConsumer());

  private static final Counter DUMMY_COUNTER = new Counter("dummy");

  private static volatile AtomicReference<TickerData> valueReference;
  private static volatile CountDownLatch latch;
  private static volatile CountDownLatch beforeLatch;
  private static volatile CountDownLatch afterLatch;
  private static volatile Counter serverCounter;

  private String regionName;
  private String hostName;
  private String title;
  private String locators;

  private VM server1;
  private VM server2;
  private VM client1;
  private VM client2;

  @Rule
  public SerializableTestName testName = new SerializableTestName();

  @Rule
  public SharedErrorCollector errorCollector = new SharedErrorCollector();

  @Before
  public void setUp() {
    regionName = getClass().getSimpleName();
    hostName = getHostName();
    title = testName.getMethodName();
    locators = getLocators();

    server1 = getVM(0);
    server2 = getVM(1);
    client1 = getVM(2);
    client2 = getVM(3);

    addIgnoredException(ConnectException.class);
    addIgnoredException(PutAllPartialResultException.class);
    addIgnoredException(RegionDestroyedException.class);
    addIgnoredException(ServerConnectivityException.class);
    addIgnoredException(SocketException.class);

    addIgnoredException("Broken pipe");
    addIgnoredException("Connection reset");
    addIgnoredException("Unexpected IOException");
  }

  @After
  public void tearDown() {
    for (VM vm : toArray(client1, client2, server1, server2)) {
      vm.invoke(() -> {
        disconnectFromDS();
        if (valueReference != null) {
          valueReference.set(null);
        }
        if (latch != null) {
          latch.countDown();
        }
        if (beforeLatch != null) {
          beforeLatch.countDown();
        }
        if (afterLatch != null) {
          afterLatch.countDown();
        }
      });
    }
  }

  /**
   * Tests putAll to one server.
   */
  @Test
  public void testOneServer() throws Exception {
    // set <PR=false, notifyBySubscription=true> to enable registerInterest and CQ
    int serverPort = server1.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort}, -1, -1,
        false, true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort}, -1, -1,
        false, true, true, true));

    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
    });

    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    client1.invoke(() -> {
      RegionFactory<String, TickerData> regionFactory = getCache().createRegionFactory(LOCAL);
      regionFactory.addCacheListener(new CountingCacheListener<>());
      regionFactory.create("localsave");

      Region<String, TickerData> region = getCache().getRegion(regionName);
      doPutAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    client1.invoke(() -> {
      beforeLatch = new CountDownLatch(1);
      afterLatch = new CountDownLatch(1);
    });

    AsyncInvocation<Void> createCqInClient1 = client1.invokeAsync(() -> {
      // create a CQ for key 10-20
      Region<String, TickerData> region = getCache().getRegion("localsave");

      CqAttributesFactory cqAttributesFactory = new CqAttributesFactory();
      cqAttributesFactory.addCqListener(new CountingCqListener<>(region));
      CqAttributes cqAttributes = cqAttributesFactory.create();

      String cqName = "EOInfoTracker";
      String query = "SELECT ALL * FROM /" + regionName
          + " ii WHERE ii.getTicker() >= '10' and ii.getTicker() < '20'";
      CqQuery cqQuery = getCache().getQueryService().newCq(cqName, query, cqAttributes);

      SelectResults<Struct> results = cqQuery.executeWithInitialResults();
      List<Struct> resultsAsList = results.asList();
      for (int i = 0; i < resultsAsList.size(); i++) {
        Struct struct = resultsAsList.get(i);
        TickerData tickerData = (TickerData) struct.get("value");
        region.put("key-" + i, tickerData);
      }

      beforeLatch.countDown();
      afterLatch.await(TIMEOUT_MILLIS, MILLISECONDS);

      cqQuery.close();
    });

    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify CQ is ready
    client1.invoke(() -> {
      beforeLatch.await(TIMEOUT_MILLIS, MILLISECONDS);

      Region<String, TickerData> region = getCache().getRegion("localsave");
      await().untilAsserted(() -> {
        assertThat(region.size()).isGreaterThan(0);
      });
    });

    // verify registerInterest result at client2
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }

      // then do update for key 10-20 to trigger CQ at server2
      // destroy key 10-14 to simulate create/update mix case
      region.removeAll(asList("key-10", "key-11", "key-12", "key-13", "key-14"));
      assertThat(region.get("key-10")).isNull();
      assertThat(region.get("key-11")).isNull();
      assertThat(region.get("key-12")).isNull();
      assertThat(region.get("key-13")).isNull();
      assertThat(region.get("key-14")).isNull();
    });

    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      Map<String, TickerData> map = new HashMap<>();
      for (int i = 10; i < 20; i++) {
        map.put("key-" + i, new TickerData(i * 10));
      }
      region.putAll(map);
    });

    // verify CQ result at client1
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion("localsave");

      for (int i = 10; i < 20; i++) {
        String key = "key-" + i;
        int price = i * 10;
        await().untilAsserted(() -> {
          TickerData tickerData = region.get(key);
          assertThat(tickerData.getPrice()).isEqualTo(price);
        });
      }

      afterLatch.countDown();
    });

    createCqInClient1.await();

    // Test Exception handling
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      Throwable thrown = catchThrowable(() -> region.putAll(null));
      assertThat(thrown).isInstanceOf(NullPointerException.class);

      region.localDestroyRegion();

      Map<String, TickerData> puts = new HashMap<>();
      for (int i = 1; i < 21; i++) {
        puts.put("key-" + i, null);
      }

      thrown = catchThrowable(() -> region.putAll(puts));
      assertThat(thrown).isInstanceOf(RegionDestroyedException.class);

      thrown = catchThrowable(() -> region.removeAll(asList("key-10", "key-11")));
      assertThat(thrown).isInstanceOf(RegionDestroyedException.class);
    });
  }

  /**
   * Tests putAll afterUpdate event contained oldValue.
   */
  @Test
  public void testOldValueInEvent() {
    // set notifyBySubscription=false to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, -1,
        false, true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2}, -1, -1,
        false, true, true, true));

    client2.invoke(() -> {
      valueReference = new AtomicReference<>();
      latch = new CountDownLatch(1);

      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator()
          .addCacheListener(new OldValueCacheListener<>(new CacheListenerAction<>(Operation.UPDATE,
              event -> {
                assertThat(event.getOldValue()).isNotNull();
                valueReference.set(event.getOldValue());
                latch.countDown();
              })));
      region.registerInterest("ALL_KEYS");
    });

    client1.invoke(() -> {
      valueReference = new AtomicReference<>();
      latch = new CountDownLatch(1);

      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator()
          .addCacheListener(new OldValueCacheListener<>(new CacheListenerAction<>(Operation.UPDATE,
              event -> {
                assertThat(event.getOldValue()).isNotNull();
                valueReference.set(event.getOldValue());
                latch.countDown();
              })));

      // create keys
      doPutAll(region, getTestMethodName(), ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      // update keys
      doPutAll(region, getTestMethodName(), ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    // the local PUTALL_UPDATE event should contain old value
    client1.invoke(() -> {
      latch.await(TIMEOUT_MILLIS, MILLISECONDS);
      assertThat(valueReference.get()).isInstanceOf(TickerData.class);
    });

    client2.invoke(() -> {
      latch.await(TIMEOUT_MILLIS, MILLISECONDS);
      assertThat(valueReference.get()).isInstanceOf(TickerData.class);
    });
  }

  /**
   * Create PR without redundancy on 2 servers with lucene index. Feed some key s. From a client, do
   * removeAll on keys in server1. During the removeAll, restart server1 and trigger the removeAll
   * to retry. The retried removeAll should return the version tag of tombstones. Do removeAll again
   * on the same key, it should get the version tag again.
   */
  @Test
  public void shouldReturnVersionTagOfTombstoneVersionWhenRemoveAllRetried() {
    // set notifyBySubscription=false to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 0, "ds1"));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, -1,
        true, true, true, true));

    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      doPutAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    // verify cache server 1, its data is from client
    server1.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isEqualTo(ONE_HUNDRED);
    });

    VersionedObjectList versions = client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      VersionedObjectList versionsToReturn = doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
      return versionsToReturn;
    });

    // client1 removeAll again
    VersionedObjectList versionsAfterRetry = client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      VersionedObjectList versionsToReturn = doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
      return versionsToReturn;
    });

    assertThat(versions.getVersionTags()).isEqualTo(versionsAfterRetry.getVersionTags());
  }

  /**
   * Tests putAll and removeAll to 2 servers. Use Case: 1) putAll from a single-threaded client to a
   * replicated region 2) putAll from a multi-threaded client to a replicated region 3)
   */
  @Test
  public void test2Server() throws Exception {
    // set notifyBySubscription=false to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, -1,
        true, true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2}, -1, -1,
        true, true, true, true));

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
    });

    // client1 add listener and putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      doPutAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().setCacheWriter(new CountingCacheWriter<>());
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify cache server 2
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().setCacheWriter(new CountingCacheWriter<>());
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // client2 verify putAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("key-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // client1 removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
    });

    // verify removeAll cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isZero();

      CountingCacheWriter countingCacheWriter =
          (CountingCacheWriter) region.getAttributes().getCacheWriter();
      assertThat(countingCacheWriter.getDestroys()).isEqualTo(ONE_HUNDRED);
    });

    // verify removeAll cache server 2
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isZero();

      CountingCacheWriter countingCacheWriter =
          (CountingCacheWriter) region.getAttributes().getCacheWriter();
      // beforeDestroys are only triggered at server1 since removeAll is submitted from client1
      assertThat(countingCacheWriter.getDestroys()).isZero();
    });

    // client2 verify removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // async putAll1 from client1
    AsyncInvocation<Void> putAll1InClient1 = client1.invokeAsync(() -> {
      doPutAll(getCache().getRegion(regionName), "async1key-", ONE_HUNDRED);
    });

    // async putAll2 from client1
    AsyncInvocation<Void> putAll2InClient1 = client1.invokeAsync(() -> {
      doPutAll(getCache().getRegion(regionName), "async2key-", ONE_HUNDRED);
    });

    putAll1InClient1.await();
    putAll2InClient1.await();

    // verify client 1 for async keys
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      long timeStamp1 = 0;
      long timeStamp2 = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("async1key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp1);
        timeStamp1 = tickerData.getTimeStamp();

        tickerData = region.getEntry("async2key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp2);
        timeStamp2 = tickerData.getTimeStamp();
      }
    });

    // verify cache server 1 for async keys
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      long timeStamp1 = 0;
      long timeStamp2 = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("async1key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp1);
        timeStamp1 = tickerData.getTimeStamp();

        tickerData = region.getEntry("async2key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp2);
        timeStamp2 = tickerData.getTimeStamp();
      }
    });

    // verify cache server 2 for async keys
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      long timeStamp1 = 0;
      long timeStamp2 = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("async1key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp1);
        timeStamp1 = tickerData.getTimeStamp();

        tickerData = region.getEntry("async2key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp2);
        timeStamp2 = tickerData.getTimeStamp();
      }
    });

    // client2 verify async putAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("async1key-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("async2key-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // async removeAll1 from client1
    AsyncInvocation<Void> removeAll1InClient1 = client1.invokeAsync(() -> {
      doRemoveAll(getCache().getRegion(regionName), "async1key-", ONE_HUNDRED);
    });

    // async removeAll2 from client1
    AsyncInvocation<Void> removeAll2InClient1 = client1.invokeAsync(() -> {
      doRemoveAll(getCache().getRegion(regionName), "async2key-", ONE_HUNDRED);
    });

    removeAll1InClient1.await();
    removeAll2InClient1.await();

    // client1 removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
    });

    // verify async removeAll cache server 1
    server1.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // verify async removeAll cache server 2
    server2.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // client2 verify async removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // server1 execute P2P putAll
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doPutAll(region, "p2pkey-", ONE_HUNDRED);

      long timeStamp = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("p2pkey-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp);
        timeStamp = tickerData.getTimeStamp();
      }
    });

    // verify cache server 2 for p2p keys
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      long timeStamp = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("p2pkey-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp);
        timeStamp = tickerData.getTimeStamp();
      }
    });

    // client2 verify p2p putAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("p2pkey-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // client1 verify p2p putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("p2pkey-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // server1 execute P2P removeAll
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doRemoveAll(region, "p2pkey-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
    });

    // verify p2p removeAll cache server 2
    server2.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // client2 verify p2p removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // client1 verify p2p removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // execute putAll on client2 for key 0-10
    client2.invoke(() -> {
      doPutAll(getCache().getRegion(regionName), "key-", 10);
    });

    // verify client1 for local invalidate
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      for (int i = 0; i < 10; i++) {
        String key = "key-" + i;

        await("entry with null value exists for " + key).until(() -> {
          Entry<String, TickerData> regionEntry = region.getEntry(key);
          return regionEntry != null && regionEntry.getValue() == null;
        });

        // local invalidate will set the value to null
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData).isNull();
      }
    });
  }

  @Test
  @Parameters({"true", "false"})
  @TestCaseName("{method}(concurrencyChecksEnabled={0})")
  public void test2NormalServer(boolean concurrencyChecksEnabled) {
    // set notifyBySubscription=false to test local-invalidates
    int serverPort1 =
        server1.invoke(() -> createServerRegion(regionName, concurrencyChecksEnabled));
    int serverPort2 =
        server2.invoke(() -> createServerRegion(regionName, concurrencyChecksEnabled));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, 59000,
        true, true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2}, -1, 59000,
        true, true, true, true));

    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
    });

    // test case 1: putAll and removeAll to server1

    // client1 add listener and putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      doPutAll(region, "case1-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("case1-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }

      region.put(String.valueOf(ONE_HUNDRED), new TickerData());
    });

    // verify cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().setCacheWriter(new CountingCacheWriter<>());

      region.localDestroy(String.valueOf(ONE_HUNDRED));
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("case1-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify cache server 2
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().setCacheWriter(new CountingCacheWriter<>());
      // normal policy will not distribute create events
      assertThat(region.size()).isZero();
    });

    // client1 removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED + 1);

      // do removeAll with some keys not exist
      doRemoveAll(region, "case1-", ONE_HUNDRED * 2);
      assertThat(region.size()).isEqualTo(1);

      region.localDestroy(String.valueOf(ONE_HUNDRED));
    });

    // verify removeAll cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isZero();

      CountingCacheWriter countingCacheWriter =
          (CountingCacheWriter) region.getAttributes().getCacheWriter();
      assertThat(countingCacheWriter.getDestroys()).isEqualTo(ONE_HUNDRED);
    });

    // verify removeAll cache server 2
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isZero();

      CountingCacheWriter countingCacheWriter =
          (CountingCacheWriter) region.getAttributes().getCacheWriter();
      // beforeDestroys are only triggered at server1 since the removeAll is submitted from client1
      assertThat(countingCacheWriter.getDestroys()).isZero();
    });

    // client2 verify removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // test case 2: putAll to server1, removeAll to server2

    // client1 add listener and putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doPutAll(region, "case2-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("case2-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("case2-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify cache server 2
    server2.invoke(() -> {
      // normal policy will not distribute create events
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // client1 removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doRemoveAll(region, "case2-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
    });

    // verify removeAll cache server 1
    server1.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isEqualTo(100);
    });

    // verify removeAll cache server 2
    server2.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // client1 verify removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(100);
      });
      doRemoveAll(region, "case2-", ONE_HUNDRED);
    });

    // test case 3: removeAll a list with duplicated keys

    // put 3 keys then removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.put("case3-1", new TickerData());
      region.put("case3-2", new TickerData());
      region.put("case3-3", new TickerData());
      assertThat(region.size()).isEqualTo(3);

      Collection<String> keys = new ArrayList<>();
      keys.add("case3-1");
      keys.add("case3-2");
      keys.add("case3-3");
      keys.add("case3-1");
      region.removeAll(keys);
      assertThat(region.size()).isZero();
    });

    // verify cache server 1
    server1.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });
  }

  @Test
  @Parameters({"true,1", "true,0", "false,1"})
  @TestCaseName("{method}(isPR={0}, redundantCopies={1})")
  public void testPRServerRVDuplicatedKeys(boolean isPR, int redundantCopies) {
    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    int serverPort1 =
        server1.invoke(() -> createServer(regionName, 0, isPR, redundantCopies, null));
    int serverPort2 =
        server2.invoke(() -> createServer(regionName, 0, isPR, redundantCopies, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, 59000,
        true, true, false, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2}, -1, 59000,
        true, true, false, true));

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
    });

    // client1 add listener and putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      doPutAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // test case 3: removeAll a list with duplicated keys

    // put 3 keys then removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.put("case3-1", new TickerData());
      region.put("case3-2", new TickerData());
      region.put("case3-3", new TickerData());
    });

    // verify cache server 2
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      await().untilAsserted(() -> {
        Entry<String, TickerData> regionEntry = region.getEntry("case3-1");
        assertThat(regionEntry).isNotNull();

        regionEntry = region.getEntry("case3-2");
        assertThat(regionEntry).isNotNull();

        regionEntry = region.getEntry("case3-3");
        assertThat(regionEntry).isNotNull();
      });
    });

    // put 3 keys then removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      Collection<String> keys = new ArrayList<>();
      keys.add("case3-1");
      keys.add("case3-2");
      keys.add("case3-3");
      keys.add("case3-1");
      region.removeAll(keys);

      Entry<String, TickerData> regionEntry = region.getEntry("case3-1");
      assertThat(regionEntry).isNull();

      regionEntry = region.getEntry("case3-2");
      assertThat(regionEntry).isNull();

      regionEntry = region.getEntry("case3-3");
      assertThat(regionEntry).isNull();
    });

    // verify cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      Entry<String, TickerData> regionEntry = region.getEntry("case3-1");
      assertThat(regionEntry).isNull();

      regionEntry = region.getEntry("case3-2");
      assertThat(regionEntry).isNull();

      regionEntry = region.getEntry("case3-3");
      assertThat(regionEntry).isNull();
    });

    // verify cache server 2
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      Entry<String, TickerData> regionEntry = region.getEntry("case3-1");
      assertThat(regionEntry).isNull();

      regionEntry = region.getEntry("case3-2");
      assertThat(regionEntry).isNull();

      regionEntry = region.getEntry("case3-3");
      assertThat(regionEntry).isNull();
    });

    // verify cache server 2
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      await().untilAsserted(() -> {
        Entry<String, TickerData> regionEntry = region.getEntry("case3-1");
        assertThat(regionEntry).isNull();

        regionEntry = region.getEntry("case3-2");
        assertThat(regionEntry).isNull();

        regionEntry = region.getEntry("case3-3");
        assertThat(regionEntry).isNull();
      });
    });
  }

  /**
   * Bug: Data inconsistency between client/server with HA (Object is missing in client cache)
   *
   * <p>
   * This is a known issue for persist region with HA.
   * <ul>
   * <li>client1 sends putAll key1,key2 to server1
   * <li>server1 applied the key1 locally, but the putAll failed due to CacheClosedException? (HA
   * test).
   * <li>Then client1 will not have key1. But when server1 is restarted, it recovered key1 from
   * persistence.
   * <li>Then data mismatch btw client and server.
   * </ul>
   *
   * <p>
   * There's a known issue which can be improved by changing the test:
   * <ul>
   * <li>edge_14640 sends a putAll to servers. But the server is shutdown due to HA test. However at
   * that time, the putAll might have put the key into local cache.
   * <li>When the server is restarted, the servers will have the key, other clients will also have
   * the key (due to register interest), but the calling client (i.e. edge_14640) will not have the
   * key, unless it's restarted.
   * </ul>
   *
   * <pre>
   * In above scenario, even changing redundantCopies to 1 will not help. This is the known issue. To improve the test, we should let all the clients restart before doing verification.
   * How to verify the result falls into above scenario?
   * 1) errors.txt, the edge_14640 has inconsistent cache compared with server's snapshot.
   * 2) grep Object_82470 *.log and found the mismatched key is originated from 14640.
   * </pre>
   */
  @Test
  @Parameters({"true", "false"})
  @TestCaseName("{method}(singleHop={0})")
  public void testBug51725(boolean singleHop) {
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 0, "ds1"));
    server2.invoke(() -> createServer(regionName, 0, true, 0, "ds1"));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, 59000,
        false, false, singleHop, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, 59000,
        false, false, singleHop, true));

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // put 3 keys then removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      // do putAll to create all buckets
      doPutAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    server2.invoke(() -> getCache().close());

    // putAll from client again
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      String message =
          String.format("Region %s putAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown = catchThrowable(() -> doPutAll(region, title, ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(PartitionOfflineException.class);

      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 3 / 2);

      int count = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        VersionTag<?> tag = ((InternalRegion) region).getVersionTag(title + i);
        if (tag != null && 1 == tag.getEntryVersion()) {
          count++;
        }
      }

      assertThat(count).isEqualTo(ONE_HUNDRED / 2);

      message = String.format(
          "Region %s removeAll at server applied partial keys due to exception.",
          region.getFullPath());

      thrown = catchThrowable(() -> doRemoveAll(region, title, ONE_HUNDRED));
      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(PartitionOfflineException.class);

      // putAll only created 50 entries, removeAll removed them. So 100 entries are all NULL
      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry(title + i);
        assertThat(regionEntry).isNull();
      }
    });

    // verify entries from client2
    client2.invoke(() -> {
      await().untilAsserted(() -> {
        Region<String, TickerData> region = getCache().getRegion(regionName);
        for (int i = 0; i < ONE_HUNDRED; i++) {
          Entry<String, TickerData> regionEntry = region.getEntry(title + i);
          assertThat(regionEntry).isNull();
        }
      });
    });
  }

  /**
   * Tests putAll to 2 PR servers.
   */
  @Test
  public void testPRServer() throws Exception {
    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 1, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, true, 1, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, 59000,
        true, true, false, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2}, -1, 59000,
        true, true, false, true));

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
    });

    // client1 add listener and putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      doPutAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().setCacheWriter(new CountingCacheWriter<>());
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify cache server 2
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().setCacheWriter(new CountingCacheWriter<>());
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
      }
    });

    // verify client2
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("key-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // client1 removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
    });

    // verify removeAll cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isZero();

      CountingCacheWriter countingCacheWriter =
          (CountingCacheWriter) region.getAttributes().getCacheWriter();
      // beforeDestroys are only triggered at primary buckets.
      // server1 and server2 each holds half of buckets
      assertThat(countingCacheWriter.getDestroys()).isEqualTo(ONE_HUNDRED / 2);
    });

    // verify removeAll cache server 2
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isZero();

      CountingCacheWriter countingCacheWriter =
          (CountingCacheWriter) region.getAttributes().getCacheWriter();
      // beforeDestroys are only triggered at primary buckets.
      // server1 and server2 each holds half of buckets
      assertThat(countingCacheWriter.getDestroys()).isEqualTo(ONE_HUNDRED / 2);
    });

    // client2 verify removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // Execute client putAll from multithread client

    // async putAll1 from client1
    AsyncInvocation<Void> putAll1InClient1 = client1.invokeAsync(() -> {
      doPutAll(getCache().getRegion(regionName), "async1key-", ONE_HUNDRED);
    });

    // async putAll2 from client1
    AsyncInvocation<Void> putAll2InClient1 = client1.invokeAsync(() -> {
      doPutAll(getCache().getRegion(regionName), "async2key-", ONE_HUNDRED);
    });

    putAll1InClient1.await();
    putAll2InClient1.await();

    // verify client 1 for async keys
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      long timeStamp1 = 0;
      long timeStamp2 = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("async1key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp1);
        timeStamp1 = tickerData.getTimeStamp();

        tickerData = region.getEntry("async2key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp2);
        timeStamp2 = tickerData.getTimeStamp();
      }
    });

    // verify cache server 2 for async keys
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      long timeStamp1 = 0;
      long timeStamp2 = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("async1key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp1);
        timeStamp1 = tickerData.getTimeStamp();

        tickerData = region.getEntry("async2key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp2);
        timeStamp2 = tickerData.getTimeStamp();
      }
    });

    // client2 verify async putAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("async1key-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("async2key-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // async removeAll1 from client1
    AsyncInvocation<Void> removeAll1InClient1 = client1.invokeAsync(() -> {
      doRemoveAll(getCache().getRegion(regionName), "async1key-", ONE_HUNDRED);
    });

    // async removeAll2 from client1
    AsyncInvocation<Void> removeAll2InClient1 = client1.invokeAsync(() -> {
      doRemoveAll(getCache().getRegion(regionName), "async2key-", ONE_HUNDRED);
    });

    removeAll1InClient1.await();
    removeAll2InClient1.await();

    // client1 removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
    });

    // verify async removeAll cache server 1
    server1.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // verify async removeAll cache server 2
    server2.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // client2 verify async removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // server1 execute P2P putAll
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doPutAll(region, "p2pkey-", ONE_HUNDRED);

      long timeStamp = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("p2pkey-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp);
        timeStamp = tickerData.getTimeStamp();
      }
    });

    // verify cache server 2 for async keys
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      long timeStamp = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("p2pkey-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp);
        timeStamp = tickerData.getTimeStamp();
      }
    });

    // client2 verify p2p putAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("p2pkey-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // client1 verify p2p putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });

      for (int i = 0; i < ONE_HUNDRED; i++) {
        Entry<String, TickerData> regionEntry = region.getEntry("p2pkey-" + i);
        assertThat(regionEntry).isNotNull();
        assertThat(regionEntry.getValue()).isNull();
      }
    });

    // server1 execute P2P removeAll
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doRemoveAll(region, "p2pkey-", ONE_HUNDRED);
      assertThat(region.size()).isZero();
    });

    // verify p2p removeAll cache server 2
    server2.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });

    // client2 verify p2p removeAll
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // client1 verify p2p removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isZero();
      });
    });

    // execute putAll on client2 for key 0-10
    client2.invoke(() -> {
      doPutAll(getCache().getRegion(regionName), "key-", 10);
    });

    // verify client1 for local invalidate
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      for (int i = 0; i < 10; i++) {
        String key = "key-" + i;

        await().until(() -> {
          Entry<String, TickerData> regionEntry = region.getEntry(key);
          return regionEntry != null && regionEntry.getValue() == null;
        });

        // local invalidate will set the value to null
        TickerData tickerData = region.getEntry("key-" + i).getValue();
        assertThat(tickerData).isNull();
      }
    });
  }

  /**
   * Checks to see if a client does a destroy that throws an exception from CacheWriter
   * beforeDestroy that the size of the region is still correct. See bug 51583.
   */
  @Test
  public void testClientDestroyOfUncreatedEntry() {
    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, -1, false,
        true, true, true));

    // server1 add cacheWriter
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      // Install cacheWriter that causes the very first destroy to fail
      region.getAttributesMutator().setCacheWriter(new ThrowingCacheWriter<>(
          new CacheListenerAction<>(Operation.DESTROY, destroys -> {
            if (destroys >= 0) {
              throw new CacheWriterException("Expected by test");
            }
          })));
    });

    assertThat(server1.invoke(() -> getCache().getRegion(regionName).size())).isZero();

    // client1 destroy
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      Throwable thrown = catchThrowable(() -> region.destroy("bogusKey"));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasCauseInstanceOf(CacheWriterException.class);
    });

    server1.invoke(() -> {
      assertThat(getCache().getRegion(regionName).size()).isZero();
    });
  }

  /**
   * Tests partial key putAll and removeAll to 2 servers with local region
   */
  @Test
  public void testPartialKeyInLocalRegion() {
    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, -1, false,
        true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, -1, -1, false,
        true, true, true));

    // server1 add cacheWriter
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      // let the server to trigger exception after created 15 keys
      region.getAttributesMutator().setCacheWriter(new ThrowingCacheWriter<>(
          new CacheListenerAction<>(Operation.CREATE, creates -> {
            if (creates >= 15) {
              throw new CacheWriterException("Expected by test");
            }
          })));
    });

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // client1 putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      String message =
          String.format("Region %s putAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown = catchThrowable(() -> doPutAll(region, "key-", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(CacheWriterException.class);
    });

    await().untilAsserted(() -> {
      assertThat(client1.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15);
      assertThat(client2.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15);
      assertThat(server1.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15);
      assertThat(server2.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15);
    });

    int sizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());

    // server1 add cacheWriter
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      // let the server to trigger exception after created 15 keys
      region.getAttributesMutator().setCacheWriter(new ThrowingCacheWriter<>(
          new CacheListenerAction<>(Operation.CREATE, creates -> {
            if (creates >= 15) {
              throw new CacheWriterException("Expected by test");
            }
          })));
    });

    // server2 add listener and putAll
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      Throwable thrown =
          catchThrowable(() -> doPutAll(region, "key-" + "again:", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(CacheWriterException.class);
    });

    int sizeOnServer2 = server2.invoke(() -> getCache().getRegion(regionName).size());
    assertThat(sizeOnServer2).isEqualTo(sizeOnServer1 + 15);

    await().untilAsserted(() -> {
      // client 1 did not register interest
      assertThat(client1.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15);
      assertThat(client2.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15 * 2);
      assertThat(server1.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15 * 2);
      assertThat(server2.invoke(() -> getCache().getRegion(regionName).size())).isEqualTo(15 * 2);
    });

    // server1 add cacheWriter
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      // server triggers exception after destroying 5 keys
      region.getAttributesMutator().setCacheWriter(new ThrowingCacheWriter<>(
          new CacheListenerAction<>(Operation.DESTROY, creates -> {
            if (creates >= 5) {
              throw new CacheWriterException("Expected by test");
            }
          })));
    });

    // client1 removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      String message =
          String.format("Region %s removeAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown = catchThrowable(() -> doRemoveAll(region, "key-", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(CacheWriterException.class);
    });

    await().untilAsserted(() -> {
      // client 1 did not register interest
      assertThat(client1.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(15 - 5);
      assertThat(client2.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(15 * 2 - 5);
      assertThat(server1.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(15 * 2 - 5);
      assertThat(server2.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(15 * 2 - 5);
    });

    // server1 add cacheWriter
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      // server triggers exception after destroying 5 keys
      region.getAttributesMutator().setCacheWriter(new ThrowingCacheWriter<>(
          new CacheListenerAction<>(Operation.DESTROY, ops -> {
            if (ops >= 5) {
              throw new CacheWriterException("Expected by test");
            }
          })));
    });

    // server2 add listener and removeAll
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      Throwable thrown =
          catchThrowable(() -> doRemoveAll(region, "key-" + "again:", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(CacheWriterException.class);
    });

    {
      await().untilAsserted(() -> {
        // client 1 did not register interest
        assertThat(client1.invoke(() -> getCache().getRegion(regionName).size()))
            .isEqualTo(15 - 5);
        assertThat(client2.invoke(() -> getCache().getRegion(regionName).size()))
            .isEqualTo(15 * 2 - 5 - 5);
        assertThat(server1.invoke(() -> getCache().getRegion(regionName).size()))
            .isEqualTo(15 * 2 - 5 - 5);
        assertThat(server2.invoke(() -> getCache().getRegion(regionName).size()))
            .isEqualTo(15 * 2 - 5 - 5);
      });
    }
  }

  /**
   * Tests partial key putAll to 2 PR servers, because putting data at server side is different
   * between PR and LR. PR does it in postPutAll. It's not running in singleHop putAll
   */
  @Test
  public void testPartialKeyInPR() throws Exception {
    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 0, "ds1"));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, true, 0, "ds1"));

    client1
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, false, false, true, true));
    client2
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, false, false, true, true));

    // server1 add slow listener
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // server2 add slow listener
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator()
          .addCacheListener(new CountingCacheListener<>(
              new CacheListenerAction<>(Operation.CREATE,
                  creates -> server2.invokeAsync(() -> closeCacheConditionally(creates, 10))),
              true));
    });

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // client1 add listener and putAll
    AsyncInvocation<Void> registerInterestAndPutAllInClient1 = client1.invokeAsync(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");

      String message =
          String.format("Region %s putAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown = catchThrowable(() -> doPutAll(region, "Key-", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(PartitionOfflineException.class);
    });

    // server2 will closeCache after created 10 keys

    registerInterestAndPutAllInClient1.await();

    int sizeOnClient1 = client1.invoke(() -> getCache().getRegion(regionName).size());
    // client2Size maybe more than client1Size
    int sizeOnClient2 = client2.invoke(() -> getCache().getRegion(regionName).size());

    // restart server2
    server2.invoke(() -> createServer(regionName, serverPort2, true, 0, "ds1"));

    await().untilAsserted(() -> {
      assertThat(server1.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(sizeOnClient2);
      assertThat(server2.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(sizeOnClient2);
    });

    // close a server to re-run the test
    server2.invoke(() -> getCache().close());
    int sizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());

    // client1 does putAll again
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      String message =
          String.format("Region %s putAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown =
          catchThrowable(() -> doPutAll(region, "key-" + "again:", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(PartitionOfflineException.class);
    });

    int newSizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());
    int newSizeOnClient1 = client1.invoke(() -> getCache().getRegion(regionName).size());
    int newSizeOnClient2 = client2.invoke(() -> getCache().getRegion(regionName).size());

    assertThat(newSizeOnServer1).isEqualTo(sizeOnServer1 + ONE_HUNDRED / 2);
    assertThat(newSizeOnClient1).isEqualTo(sizeOnClient1 + ONE_HUNDRED / 2);
    assertThat(newSizeOnClient2).isEqualTo(sizeOnClient2 + ONE_HUNDRED / 2);

    // restart server2
    server2.invoke(() -> createServer(regionName, serverPort2, true, 0, "ds1"));
    sizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());
    int sizeOnServer2 = server2.invoke(() -> getCache().getRegion(regionName).size());
    assertThat(sizeOnServer2).isEqualTo(sizeOnServer1);

    // server1 execute P2P putAll
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      // let the server to trigger exception after created 15 keys
      region.getAttributesMutator().setCacheWriter(new ThrowingCacheWriter<>(
          new CacheListenerAction<>(Operation.CREATE, creates -> {
            if (creates >= 15) {
              throw new CacheWriterException("Expected by test");
            }
          })));
    });

    // server2 add listener and putAll
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      Throwable thrown =
          catchThrowable(() -> doPutAll(region, "key-" + "once again:", ONE_HUNDRED));
      assertThat(thrown)
          .isInstanceOf(CacheWriterException.class)
          .hasMessageContaining("Expected by test");
    });

    newSizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());
    int newSizeOnServer2 = server2.invoke(() -> getCache().getRegion(regionName).size());
    assertThat(newSizeOnServer1).isEqualTo(sizeOnServer1 + 15);
    assertThat(newSizeOnServer2).isEqualTo(sizeOnServer2 + 15);
  }

  /**
   * Tests partial key putAll to 2 PR servers, because putting data at server side is different
   * between PR and LR. PR does it in postPutAll. This is a singlehop putAll test.
   */
  @Test
  public void testPartialKeyInPRSingleHop() throws Exception {
    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 0, "ds1"));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, true, 0, "ds1"));

    client1
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, false, false, true, false));
    client2
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, false, false, true, true));

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // do some putAll to get ClientMetaData for future putAll
    client1.invoke(() -> {
      doPutAll(getCache().getRegion(regionName), "key-", ONE_HUNDRED);
    });

    await().untilAsserted(() -> {
      assertThat(client1.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
      assertThat(client2.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
      assertThat(server1.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
      assertThat(server2.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
    });

    // server1 add slow listener
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // add a listener that will close the cache at the 10th update

    // server2 add slow listener
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator()
          .addCacheListener(new CountingCacheListener<>(
              new CacheListenerAction<>(Operation.CREATE,
                  creates -> server2.invokeAsync(() -> closeCacheConditionally(creates, 10))),
              true));
    });

    // client1 add listener and putAll
    AsyncInvocation<Void> addListenerAndPutAllInClient1 = client1.invokeAsync(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());

      String message =
          String.format("Region %s putAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown = catchThrowable(() -> doPutAll(region, title, ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(PartitionOfflineException.class);
    });

    // server2 will closeCache after creating 10 keys

    addListenerAndPutAllInClient1.await();

    // restart server2
    System.out.println("restarting server 2");
    server2.invoke(() -> createServer(regionName, serverPort2, true, 0, "ds1"));

    // Test Case1: Trigger singleHop putAll. Stop server2 in middle.
    // ONE_HUNDRED_ENTRIES/2 + X keys will be created at servers. i.e. X keys at server2,
    // ONE_HUNDRED_ENTRIES/2 keys at server1.
    // The client should receive a PartialResultException due to PartitionOffline

    // close a server to re-run the test
    server2.invoke(() -> getCache().close());

    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      String message =
          String.format("Region %s putAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown =
          catchThrowable(() -> doPutAll(region, title + "again:", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(PartitionOfflineException.class);
    });

    // Test Case 2: based on case 1, but this time, there should be no X keys
    // created on server2.

    // restart server2
    server2.invoke(() -> createServer(regionName, serverPort2, true, 0, "ds1"));

    // add a cacheWriter for server to fail putAll after it created cacheWriterAllowedKeyNum keys
    int cacheWriterAllowedKeyNum = 16; // TODO

    // server1 add cacheWriter to throw exception after created some keys
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().setCacheWriter(new ThrowingCacheWriter<>(
          new CacheListenerAction<>(Operation.CREATE, creates -> {
            if (creates >= cacheWriterAllowedKeyNum) {
              throw new CacheWriterException("Expected by test");
            }
          })));
    });

    // client1 does putAll once more
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      String message =
          String.format("Region %s putAll at server applied partial keys due to exception.",
              region.getFullPath());

      Throwable thrown =
          catchThrowable(() -> doPutAll(region, title + "once more:", ONE_HUNDRED));

      assertThat(thrown)
          .isInstanceOf(ServerOperationException.class)
          .hasMessageContaining(message);
      assertThat(thrown.getCause())
          .isInstanceOf(CacheWriterException.class)
          .hasMessageContaining("Expected by test");
    });
  }

  /**
   * Set redundancy=1 to see if retry succeeded after PRE This is a singlehop putAll test.
   */
  @Test
  public void testPartialKeyInPRSingleHopWithRedundancy() throws Exception {
    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 1, "ds1"));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, true, 1, "ds1"));

    client1
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, false, false, true, false));
    client2
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, false, false, true, true));

    // client2 add listener
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // do some putAll to get ClientMetaData for future putAll
    client1.invoke(() -> {
      doPutAll(getCache().getRegion(regionName), "key-", ONE_HUNDRED);
    });

    await().untilAsserted(() -> {
      assertThat(client1.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
      assertThat(client2.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
      assertThat(server1.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
      assertThat(server2.invoke(() -> getCache().getRegion(regionName).size()))
          .isEqualTo(ONE_HUNDRED);
    });

    // server1 add slow listener
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // server2 add slow listener
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator()
          .addCacheListener(new CountingCacheListener<>(
              new CacheListenerAction<>(Operation.CREATE,
                  creates -> server2.invokeAsync(() -> closeCacheConditionally(creates, 10))),
              true));
    });

    // client1 add listener and putAll
    AsyncInvocation<Void> registerInterestAndPutAllInClient1 = client1.invokeAsync(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");

      // create keys
      doPutAll(region, title, ONE_HUNDRED);
    });

    // server2 will closeCache after created 10 keys

    registerInterestAndPutAllInClient1.await();

    int sizeOnClient1 = client1.invoke(() -> getCache().getRegion(regionName).size());
    int sizeOnClient2 = client2.invoke(() -> getCache().getRegion(regionName).size());
    int sizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());
    // putAll should succeed after retry
    assertThat(sizeOnClient1).isEqualTo(sizeOnServer1);
    assertThat(sizeOnClient2).isEqualTo(sizeOnServer1);

    // restart server2
    server2.invoke(() -> createServer(regionName, serverPort2, true, 1, "ds1"));

    sizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());
    int sizeOnServer2 = server2.invoke(() -> getCache().getRegion(regionName).size());
    assertThat(sizeOnServer1).isEqualTo(sizeOnClient2);
    assertThat(sizeOnServer2).isEqualTo(sizeOnClient2);

    // close a server to re-run the test
    server2.invoke(() -> getCache().close());

    // client1 does putAll again
    client1.invoke(() -> {
      doPutAll(getCache().getRegion(regionName), title + "again:", ONE_HUNDRED);
    });

    int newSizeOnServer1 = server1.invoke(() -> getCache().getRegion(regionName).size());
    int newSizeOnClient1 = client1.invoke(() -> getCache().getRegion(regionName).size());
    int newSizeOnClient2 = client2.invoke(() -> getCache().getRegion(regionName).size());

    // putAll should succeed, all the numbers should match
    assertThat(newSizeOnClient1).isEqualTo(newSizeOnServer1);
    assertThat(newSizeOnClient2).isEqualTo(newSizeOnServer1);
  }

  /**
   * Tests bug 41403: let 2 sub maps both failed with partial key applied. This is a singlehop
   * putAll test.
   */
  @Test
  public void testEventIdMisorderInPRSingleHop() throws Exception {
    VM server3 = client2;

    int[] serverPorts = new int[3];

    // set <true, false> means <PR=true, notifyBySubscription=false> to test local-invalidates
    serverPorts[0] = server1.invoke(() -> createServer(regionName, 0, true, 0, null));
    serverPorts[1] = server2.invoke(() -> createServer(regionName, 0, true, 0, null));
    serverPorts[2] = server3.invoke(() -> createServer(regionName, 0, true, 0, null));

    client1.invoke(() -> createClient(regionName, hostName, serverPorts, -1, -1, false, true, true,
        false));

    // Create local client with region
    Properties config = new Properties();
    config.setProperty(MCAST_PORT, "0");
    config.setProperty(LOCATORS, "");

    getCache(config);

    String poolName = createPool(hostName, serverPorts, true, -1,
        -1, null, null, PoolManager.createFactory(), -1,
        -1, -2, -1);

    RegionFactory<String, TickerData> regionFactory = getCache().createRegionFactory(LOCAL);
    regionFactory.setPoolName(poolName);
    Region<String, TickerData> myRegion = regionFactory.create(regionName);

    // do some putAll to get ClientMetaData for future putAll
    client1.invoke(() -> {
      doPutAll(getCache().getRegion(regionName), "key-", ONE_HUNDRED);
    });

    // register interest and add listener
    Counter clientCounter = new Counter("client");
    myRegion.getAttributesMutator().addCacheListener(new CountingCacheListener<>(clientCounter));
    myRegion.registerInterest("ALL_KEYS");

    // server1 add slow listener
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator()
          .addCacheListener(new CountingCacheListener<>(
              new CacheListenerAction<>(Operation.CREATE,
                  creates -> server1.invokeAsync(() -> closeCacheConditionally(creates, 10))),
              true));
    });

    // server2 add slow listener
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator()
          .addCacheListener(new CountingCacheListener<>(
              new CacheListenerAction<>(Operation.CREATE,
                  creates -> server2.invokeAsync(() -> closeCacheConditionally(creates, 10))),
              true));
    });

    // server3 add slow listener
    server3.invoke(() -> {
      serverCounter = new Counter("server3");
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(
          new CountingCacheListener<>(serverCounter, true));
    });

    // client1 add listener and putAll
    AsyncInvocation<Void> addListenerAndPutAllInClient1 = client1.invokeAsync(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      doPutAll(region, title, ONE_HUNDRED);
    });

    // server1 and server2 will closeCache after created 10 keys

    addListenerAndPutAllInClient1.await();

    // server3 print counter
    server3.invoke(() -> {
      assertThat(serverCounter.getCreates()).isEqualTo(ONE_HUNDRED);
      assertThat(serverCounter.getUpdates()).isZero();
    });

    await().untilAsserted(() -> {
      assertThat(clientCounter.getCreates()).isEqualTo(ONE_HUNDRED);
    });

    assertThat(clientCounter.getUpdates()).isZero();
  }

  /**
   * Tests while putAll to 2 distributed servers, one server failed over Add a listener to slow down
   * the processing of putAll
   */
  @Test
  public void test2FailOverDistributedServer() throws Exception {
    // set notifyBySubscription=true to test register interest
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, true, true, true, true));
    client2
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort2, serverPort1}, -1,
            -1, true, true, true, true));

    // server1 add slow listener
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // server2 add slow listener
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // client1 registerInterest
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // client2 registerInterest
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // async putAll1 from client1
    AsyncInvocation<Void> putAllInClient1 = client1.invokeAsync(() -> {
      try {
        doPutAll(getCache().getRegion(regionName), "async1key-", ONE_HUNDRED);
      } catch (Exception e) {
        // ignored
      }
    });

    // stop cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.get("async1key-1")).isNotNull();
      });
      stopCacheServer(serverPort1);
    });

    putAllInClient1.await();

    // verify cache server 2 for async keys
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      long timeStamp = 0;
      for (int i = 0; i < ONE_HUNDRED; i++) {
        TickerData tickerData = region.getEntry("async1key-" + i).getValue();
        assertThat(tickerData.getPrice()).isEqualTo(i);
        assertThat(tickerData.getTimeStamp()).isGreaterThanOrEqualTo(timeStamp);
        timeStamp = tickerData.getTimeStamp();
      }
    });
  }

  /**
   * Tests while putAll timeout's exception
   */
  @Test
  public void testClientTimeOut() {
    // set notifyBySubscription=true to test register interest
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, -1,
            -1, true, true, true, true));
    client2
        .invoke(() -> createClient(regionName, hostName, new int[] {serverPort2, serverPort1}, -1,
            -1, true, true, true, true));

    // server1 add slow listener
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // server2 add slow listener
    server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // client1 execute putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      Throwable thrown = catchThrowable(() -> doPutAll(region, "key-", ONE_THOUSAND));
      assertThat(thrown)
          .isInstanceOf(ServerConnectivityException.class);
    });
  }

  /**
   * Tests while putAll timeout at endpoint1 and switch to endpoint2
   */
  @Test
  public void testEndPointSwitch() {
    // set notifyBySubscription=true to test register interest
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, 1,
        -1, true, true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2, serverPort1}, 1,
        -1, false, true, true, true));

    // only add slow listener to server1, because we wish it to succeed

    // server1 add slow listener
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>(true));
    });

    // only register interest on client2

    // client2 registerInterest
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // putAll from client1
    client1.invoke(() -> {
      try {
        doPutAll(getCache().getRegion(regionName), title, ONE_HUNDRED);
      } catch (Exception e) {
        // ignored
      }
    });

    // verify Bridge client2 for keys arrived finally
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED);
      });
    });
  }

  /**
   * Tests while putAll to 2 distributed servers, one server failed over Add a listener to slow down
   * the processing of putAll
   */
  @Test
  public void testHADRFailOver() throws Exception {
    addIgnoredException(DistributedSystemDisconnectedException.class);

    // set notifyBySubscription=true to test register interest
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    // set queueRedundancy=1
    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1, serverPort2}, 1,
        -1, true, true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2, serverPort1}, 1,
        -1, false, true, true, true));

    // client1 registerInterest
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.registerInterest("ALL_KEYS");
    });

    // client2 registerInterest
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.getAttributesMutator().addCacheListener(new CountingCacheListener<>());
      region.registerInterest("ALL_KEYS");
    });

    // async putAll1 from server2
    AsyncInvocation<Void> putAllInServer2 = server2.invokeAsync(() -> {
      doPutAll(getCache().getRegion(regionName), "server2-key-", ONE_HUNDRED);
    });

    // async putAll1 from client1
    AsyncInvocation<Void> putAllInClient1 = client1.invokeAsync(() -> {
      doPutAll(getCache().getRegion(regionName), "client1-key-", ONE_HUNDRED);
    });

    // stop cache server 1
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.get("server2-key-1")).isNotNull();
        assertThat(region.get("client1-key-1")).isNotNull();
      });
      stopCacheServer(serverPort1);
    });

    putAllInServer2.await();
    putAllInClient1.await();

    // verify Bridge client2 for async keys
    client2.invokeAsync(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      await().untilAsserted(() -> {
        assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);
      });
    });
  }

  @Test
  public void testVersionsOnClientsWithNotificationsOnly() {
    // set notifyBySubscription=true to test register interest
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, true, 0, null));

    // set queueRedundancy=1
    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, 0, 59000, true,
        true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2}, 0, 59000, true,
        true, true, true));

    // client1 putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doPutAll(region, "key-", ONE_HUNDRED * 2);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);
    });

    // client2 versions collection
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.registerInterest("ALL_KEYS");
    });

    // client1 versions collection
    List<VersionTag<?>> client1Versions = client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      List<VersionTag<?>> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
        versions.add(tag);
      }

      return versions;
    });

    // client2 versions collection
    List<VersionTag<?>> client2Versions = client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      List<VersionTag<?>> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
        versions.add(tag);
      }

      return versions;
    });

    assertThat(client1Versions.size()).isEqualTo(ONE_HUNDRED * 2);

    for (VersionTag<?> tag : client1Versions) {
      assertThat(client2Versions).contains(tag);
    }
  }

  /**
   * basically same test as testVersionsOnClientsWithNotificationsOnly but also do a removeAll
   */
  @Test
  public void testRAVersionsOnClientsWithNotificationsOnly() {
    // set notifyBySubscription=true to test register interest
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, true, 0, null));
    int serverPort2 = server2.invoke(() -> createServer(regionName, 0, true, 0, null));

    // set queueRedundancy=1
    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, 0, 59000, true,
        true, true, true));
    client2.invoke(() -> createClient(regionName, hostName, new int[] {serverPort2}, 0, 59000, true,
        true, true, true));

    // client1 putAll+removeAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      doPutAll(region, "key-", ONE_HUNDRED * 2);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    // client2 versions collection
    client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.registerInterest("ALL_KEYS");
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    // client1 versions collection
    List<VersionTag<?>> client1RAVersions = client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      assertThat(entries.size()).isEqualTo(ONE_HUNDRED * 2);

      List<VersionTag<?>> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
        versions.add(tag);
      }

      return versions;
    });

    // client2 versions collection
    List<VersionTag<?>> client2RAVersions = client2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      assertThat(entries.size()).isEqualTo(ONE_HUNDRED * 2);

      List<VersionTag<?>> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
        versions.add(tag);
      }
      return versions;
    });

    assertThat(client1RAVersions.size()).isEqualTo(ONE_HUNDRED * 2);

    for (VersionTag<?> tag : client1RAVersions) {
      assertThat(client2RAVersions).contains(tag);
    }
  }

  @Test
  public void testVersionsOnServersWithNotificationsOnly() {
    VM server3 = client2;

    // set notifyBySubscription=true to test register interest
    server1.invoke(() -> createServer(regionName, 0, true, 1, null));
    server2.invoke(() -> createServer(regionName, 0, true, 1, null));
    int serverPort3 = server3.invoke(() -> createServer(regionName, 0, true, 1, null));

    // set queueRedundancy=1
    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort3}, 0, 59000, true,
        true, true, true));

    // client2 versions collection
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.registerInterest("ALL_KEYS");
    });

    // client1 putAll
    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      doPutAll(region, "key-", ONE_HUNDRED * 2);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);
    });

    // server1 versions collection
    List<String> expectedVersions = server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      PartitionedRegionDataStore dataStore = ((PartitionedRegion) region).getDataStore();
      Set<BucketRegion> buckets = dataStore.getAllLocalPrimaryBucketRegions();

      List<String> versions = new ArrayList<>();
      for (BucketRegion bucketRegion : buckets) {
        RegionMap entries = bucketRegion.entries;
        for (Object key : entries.keySet()) {
          RegionEntry regionEntry = entries.getEntry(key);
          VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
          versions.add(key + " " + tag);
        }
      }

      return versions;
    });

    // Let client be updated with all keys.
    sleep(1000);

    // client1 versions collection
    List<String> actualVersions = client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      List<String> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
        tag.setMemberID(null);
        versions.add(key + " " + tag);
      }

      return versions;
    });

    assertThat(actualVersions).hasSize(ONE_HUNDRED * 2);

    for (String keyTag : expectedVersions) {
      assertThat(actualVersions).contains(keyTag);
    }
  }

  /**
   * Same test as testVersionsOnServersWithNotificationsOnly but also does a removeAll
   */
  @Test
  public void testRAVersionsOnServersWithNotificationsOnly() {
    VM server3 = client2;

    // set notifyBySubscription=true to test register interest
    server1.invoke(() -> createServer(regionName, 0, true, 1, null));
    server2.invoke(() -> createServer(regionName, 0, true, 1, null));
    int serverPort3 = server3.invoke(() -> createServer(regionName, 0, true, 1, null));

    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort3}, 0, 59000, true,
        true, true, true));

    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      region.registerInterest("ALL_KEYS");
    });

    server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      doPutAll(region, "key-", ONE_HUNDRED * 2);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    List<String> expectedRAVersions = server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      PartitionedRegionDataStore dataStore = ((PartitionedRegion) region).getDataStore();
      Set<BucketRegion> buckets = dataStore.getAllLocalPrimaryBucketRegions();

      List<String> versions = new ArrayList<>();
      for (BucketRegion bucketRegion : buckets) {
        RegionMap entries = bucketRegion.entries;
        for (Object key : entries.keySet()) {
          RegionEntry regionEntry = entries.getEntry(key);
          VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
          versions.add(key + " " + tag);
        }
      }

      return versions;
    });

    // Let client be updated with all keys.
    sleep(1000);

    List<String> actualRAVersions = client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      assertThat(entries.size()).isEqualTo(ONE_HUNDRED * 2);

      List<String> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
        tag.setMemberID(null);
        versions.add(key + " " + tag);
      }
      return versions;
    });

    assertThat(actualRAVersions).hasSize(ONE_HUNDRED * 2);

    for (String keyTag : expectedRAVersions) {
      assertThat(actualRAVersions).contains(keyTag);
    }
  }

  @Test
  public void testVersionsOnReplicasAfterPutAllAndRemoveAll() {
    // set notifyBySubscription=true to test register interest
    int serverPort1 = server1.invoke(() -> createServer(regionName, 0, false, 0, null));
    server2.invoke(() -> createServer(regionName, 0, false, 0, null));

    // set queueRedundancy=1
    client1.invoke(() -> createClient(regionName, hostName, new int[] {serverPort1}, 0, 59000, true,
        true, true, true));

    // client1 putAll
    client1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);

      doPutAll(region, "key-", ONE_HUNDRED * 2);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED * 2);

      doRemoveAll(region, "key-", ONE_HUNDRED);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);
    });

    // client1 versions collection
    List<VersionTag<?>> client1Versions = server1.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      assertThat(entries.size()).isEqualTo(ONE_HUNDRED * 2);

      List<VersionTag<?>> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        VersionTag<?> tag = regionEntry.getVersionStamp().asVersionTag();
        versions.add(tag);
      }

      return versions;
    });

    // client2 versions collection
    List<VersionTag<?>> client2Versions = server2.invoke(() -> {
      Region<String, TickerData> region = getCache().getRegion(regionName);
      assertThat(region.size()).isEqualTo(ONE_HUNDRED);

      RegionMap entries = ((InternalRegion) region).getRegionMap();
      assertThat(entries.size()).isEqualTo(ONE_HUNDRED * 2);

      List<VersionTag<?>> versions = new ArrayList<>();
      for (Object key : entries.keySet()) {
        RegionEntry regionEntry = entries.getEntry(key);
        versions.add(regionEntry.getVersionStamp().asVersionTag());
      }

      return versions;
    });

    assertThat(client1Versions.size()).isEqualTo(ONE_HUNDRED * 2);

    for (VersionTag<?> tag : client2Versions) {
      tag.setMemberID(null);
      assertThat(client1Versions).contains(tag);
    }
  }

  /**
   * this method creates a client connection pool and configures it. If the ports array is not empty
   * it is used to configure the client pool. Otherwise the pool is configured to use the dunit
   * locator.
   */
  private String createPool(String host, int[] ports, boolean subscribe, int redundancy,
      int connectionsPerServer, String serverGroup, String poolName, PoolFactory poolFactory,
      int pingInterval, int idleTimeout, int lifetimeTimeout, int statisticInterval) {
    if (ports.length == 0) {
      poolFactory.addLocator(host, getLocatorPort());
    } else {
      for (int port : ports) {
        poolFactory.addServer(host, port);
      }
    }

    // TODO - probably should pass in minConnections rather than connections per server
    if (connectionsPerServer != -1) {
      poolFactory.setMinConnections(connectionsPerServer * ports.length);
    }
    if (pingInterval != -1) {
      poolFactory.setPingInterval(pingInterval);
    }
    if (idleTimeout != -1) {
      poolFactory.setIdleTimeout(idleTimeout);
    }
    if (statisticInterval != -1) {
      poolFactory.setStatisticInterval(statisticInterval);
    }
    if (lifetimeTimeout != -2) {
      poolFactory.setLoadConditioningInterval(lifetimeTimeout);
    }
    if (subscribe) {
      poolFactory.setSubscriptionEnabled(true);
      poolFactory.setSubscriptionRedundancy(redundancy);
      poolFactory.setSubscriptionAckInterval(1);
    }
    if (serverGroup != null) {
      poolFactory.setServerGroup(serverGroup);
    }
    Pool pool = poolFactory.create(poolName == null ? "testPool" : poolName);
    return pool.getName();
  }

  private int createServerRegion(String regionName, boolean concurrencyChecksEnabled)
      throws IOException {
    RegionFactory<String, TickerData> regionFactory = getCache().createRegionFactory();
    regionFactory.setConcurrencyChecksEnabled(concurrencyChecksEnabled);
    regionFactory.setScope(Scope.DISTRIBUTED_ACK);
    regionFactory.setDataPolicy(DataPolicy.NORMAL);
    regionFactory.create(regionName);

    CacheServer cacheServer = getCache().addCacheServer();
    cacheServer.setPort(0);
    cacheServer.start();
    return cacheServer.getPort();
  }

  private int createServer(String regionName, int serverPort, boolean createPR, int redundantCopies,
      String diskStoreName) throws IOException {
    Properties config = new Properties();
    config.setProperty(LOCATORS, locators);

    getCache(config);

    // create diskStore if required
    if (diskStoreName != null) {
      DiskStore diskStore = getCache().findDiskStore(diskStoreName);
      if (diskStore == null) {
        getCache().createDiskStoreFactory().setDiskDirs(getDiskDirs()).create(diskStoreName);
      }
    }

    // In this test, no cacheLoader should be defined, otherwise, it will create a value for
    // destroyed key factory.setCacheLoader(new CacheServerCacheLoader());

    RegionFactory<String, TickerData> regionFactory;
    if (createPR) {
      if (diskStoreName == null) {
        regionFactory = getCache().createRegionFactory(PARTITION);
        // enable concurrency checks (not for disk now - disk doesn't support versions yet)
        regionFactory.setConcurrencyChecksEnabled(true);
      } else {
        regionFactory = getCache().createRegionFactory(PARTITION_PERSISTENT);
        regionFactory.setDiskStoreName(diskStoreName);
      }

      PartitionAttributesFactory<String, TickerData> partitionAttributesFactory =
          new PartitionAttributesFactory<>();
      partitionAttributesFactory.setRedundantCopies(redundantCopies);
      partitionAttributesFactory.setTotalNumBuckets(10);
      regionFactory.setPartitionAttributes(partitionAttributesFactory.create());

    } else {
      if (diskStoreName == null) {
        regionFactory = getCache().createRegionFactory(REPLICATE);
      } else {
        regionFactory = getCache().createRegionFactory(REPLICATE_PERSISTENT);
        regionFactory.setDiskStoreName(diskStoreName);
      }
    }

    Region<String, TickerData> region = regionFactory.create(regionName);

    if (createPR) {
      assertThat(region).isInstanceOf(PartitionedRegion.class);
    } else {
      assertThat(region).isInstanceOf(DistributedRegion.class);
    }

    CacheServer cacheServer = getCache().addCacheServer();
    cacheServer.setPort(serverPort);
    cacheServer.setMaxThreads(0);
    cacheServer.start();
    return cacheServer.getPort();
  }

  private void createClient(String regionName, String serverHost, int[] serverPorts, int redundancy,
      int readTimeOut, boolean receiveInvalidates, boolean concurrencyChecks,
      boolean enableSingleHop, boolean subscriptionEnabled) {
    Properties config = new Properties();
    config.setProperty(MCAST_PORT, "0");
    config.setProperty(LOCATORS, "");

    getCache(config);

    RegionFactory<String, TickerData> regionFactory = getCache().createRegionFactory(LOCAL);

    if (concurrencyChecks) {
      regionFactory.setConcurrencyChecksEnabled(true);
    }

    String poolName;
    if (readTimeOut > 0) {
      poolName = "myPool";

      PoolFactory poolFactory = PoolManager.createFactory();
      for (int serverPort : serverPorts) {
        poolFactory.addServer(serverHost, serverPort);
      }

      poolFactory.setReadTimeout(readTimeOut)
          .setSubscriptionEnabled(subscriptionEnabled)
          .setPRSingleHopEnabled(enableSingleHop)
          .create(poolName);

    } else {
      poolName = createPool(serverHost, serverPorts, true,
          redundancy, -1, null, null, PoolManager.createFactory(), -1, -1, -2, -1);
    }

    regionFactory.setPoolName(poolName);

    Region<String, TickerData> region = regionFactory.create(regionName);

    if (receiveInvalidates) {
      region.registerInterestRegex(".*", false, false);
    }
  }

  private void doPutAll(Region<String, TickerData> region, String keyPrefix, int entryCount) {
    Map<String, TickerData> map = new LinkedHashMap<>();
    for (int i = 0; i < entryCount; i++) {
      map.put(keyPrefix + i, new TickerData(i));
    }
    region.putAll(map);
  }

  private VersionedObjectList doRemoveAll(Region<String, TickerData> region, String keyPrefix,
      int entryCount) {
    InternalRegion internalRegion = (InternalRegion) region;

    InternalEntryEvent event =
        EntryEventImpl.create(internalRegion, Operation.REMOVEALL_DESTROY, null, null,
            null, false, internalRegion.getMyId());
    event.disallowOffHeapValues();

    Collection<Object> keys = new ArrayList<>();
    for (int i = 0; i < entryCount; i++) {
      keys.add(keyPrefix + i);
    }

    DistributedRemoveAllOperation removeAllOp =
        new DistributedRemoveAllOperation(event, keys.size(), false);

    return internalRegion.basicRemoveAll(keys, removeAllOp, null);
  }

  /**
   * Stops the cache server specified by port
   */
  private void stopCacheServer(int port) {
    boolean foundServer = false;
    for (CacheServer cacheServer : getCache().getCacheServers()) {
      if (cacheServer.getPort() == port) {
        cacheServer.stop();
        assertThat(cacheServer.isRunning()).isFalse();
        foundServer = true;
        break;
      }
    }
    assertThat(foundServer).isTrue();
  }

  private void closeCacheConditionally(int ops, int threshold) {
    if (ops >= threshold) {
      getCache().close();
    }
  }

  private static <T> Consumer<T> emptyConsumer() {
    return input -> {
      // nothing
    };
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static class Counter implements Serializable {

    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger updates = new AtomicInteger();
    private final AtomicInteger invalidates = new AtomicInteger();
    private final AtomicInteger destroys = new AtomicInteger();

    private final String owner;

    Counter(String owner) {
      this.owner = owner;
    }

    int getCreates() {
      return creates.get();
    }

    void incCreates() {
      creates.incrementAndGet();
    }

    int getUpdates() {
      return updates.get();
    }

    void incUpdates() {
      updates.incrementAndGet();
    }

    int getInvalidates() {
      return invalidates.get();
    }

    void incInvalidates() {
      invalidates.incrementAndGet();
    }

    int getDestroys() {
      return destroys.get();
    }

    void incDestroys() {
      destroys.incrementAndGet();
    }

    @Override
    public String toString() {
      return "Owner=" + owner + ",create=" + creates + ",update=" + updates
          + ",invalidate=" + invalidates + ",destroy=" + destroys;
    }
  }

  /**
   * Defines an action to be run after the specified operation is invoked by CacheListener.
   */
  private static class CacheListenerAction<T> {

    private final Operation operation;
    private final Consumer<T> consumer;

    private CacheListenerAction(Operation operation, Consumer<T> consumer) {
      this.operation = operation;
      this.consumer = consumer;
    }

    void run(Operation operation, T value) {
      if (operation == this.operation) {
        consumer.accept(value);
      }
    }
  }

  private static class CountingCacheListener<K, V> extends CacheListenerAdapter<K, V> {

    private final Counter counter;
    private final CacheListenerAction<Integer> operationCountAction;
    private final boolean delay;
    private final long sleepMillis;

    CountingCacheListener() {
      this(false);
    }

    CountingCacheListener(boolean delay) {
      this(DUMMY_COUNTER, delay);
    }

    CountingCacheListener(Counter counter) {
      this(counter, false);
    }

    CountingCacheListener(Counter counter, boolean delay) {
      this(counter, EMPTY_INTEGER_ACTION, delay, 50);
    }

    CountingCacheListener(CacheListenerAction<Integer> operationCountAction, boolean delay) {
      this(DUMMY_COUNTER, operationCountAction, delay, 50);
    }

    private CountingCacheListener(Counter counter,
        CacheListenerAction<Integer> operationCountAction, boolean delay, long sleepMillis) {
      this.counter = counter;
      this.operationCountAction = operationCountAction;
      this.delay = delay;
      this.sleepMillis = sleepMillis;
    }

    @Override
    public void afterCreate(EntryEvent<K, V> event) {
      counter.incCreates();
      operationCountAction.run(Operation.CREATE, counter.getCreates());
      if (delay) {
        sleep(sleepMillis);
      }
    }

    @Override
    public void afterUpdate(EntryEvent<K, V> event) {
      counter.incUpdates();
      operationCountAction.run(Operation.UPDATE, counter.getUpdates());
      if (delay) {
        sleep(sleepMillis);
      }
    }

    @Override
    public void afterInvalidate(EntryEvent<K, V> event) {
      counter.incInvalidates();
      operationCountAction.run(Operation.INVALIDATE, counter.getInvalidates());
    }

    @Override
    public void afterDestroy(EntryEvent<K, V> event) {
      counter.incDestroys();
      operationCountAction.run(Operation.DESTROY, counter.getDestroys());
    }
  }

  private static class OldValueCacheListener<K, V> extends CacheListenerAdapter<K, V> {

    private final CacheListenerAction<EntryEvent<K, V>> entryEventAction;

    OldValueCacheListener(CacheListenerAction<EntryEvent<K, V>> entryEventAction) {
      this.entryEventAction = entryEventAction;
    }

    @Override
    public void afterUpdate(EntryEvent<K, V> event) {
      entryEventAction.run(Operation.UPDATE, event);
    }
  }

  private static class TickerData implements DataSerializable {

    private String ticker;
    private int price;
    private long timeStamp = System.currentTimeMillis();

    public TickerData() {
      // nothing
    }

    TickerData(int price) {
      this(String.valueOf(price), price);
    }

    private TickerData(String ticker, int price) {
      this.ticker = ticker;
      this.price = price;
    }

    public String getTicker() {
      return ticker;
    }

    private int getPrice() {
      return price;
    }

    private long getTimeStamp() {
      return timeStamp;
    }

    @Override
    public void toData(DataOutput out) throws IOException {
      DataSerializer.writeString(ticker, out);
      out.writeInt(price);
      out.writeLong(timeStamp);
    }

    @Override
    public void fromData(DataInput in) throws IOException {
      ticker = DataSerializer.readString(in);
      price = in.readInt();
      timeStamp = in.readLong();
    }

    @Override
    public String toString() {
      return "Price=" + price;
    }
  }

  private static class CountingCqListener<K, V> implements CqListener {

    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger updates = new AtomicInteger();

    private final Region<K, V> region;

    CountingCqListener(Region<K, V> region) {
      this.region = region;
    }

    @Override
    public void onEvent(CqEvent cqEvent) {
      if (cqEvent.getQueryOperation() == Operation.DESTROY) {
        return;
      }

      K key = (K) cqEvent.getKey();
      V newValue = (V) cqEvent.getNewValue();

      if (newValue == null) {
        region.create(key, null);
        creates.incrementAndGet();
      } else {
        region.put(key, newValue);
        updates.incrementAndGet();
      }
    }

    @Override
    public void onError(CqEvent cqEvent) {
      // nothing
    }

    int getCreates() {
      return creates.get();
    }

    int getUpdates() {
      return updates.get();
    }
  }

  private static class CountingCacheWriter<K, V> extends CacheWriterAdapter<K, V> {

    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger destroys = new AtomicInteger();

    @Override
    public synchronized void beforeCreate(EntryEvent<K, V> event) {
      creates.incrementAndGet();
    }

    @Override
    public void beforeDestroy(EntryEvent<K, V> event) {
      destroys.incrementAndGet();
    }

    int getCreates() {
      return creates.get();
    }

    int getDestroys() {
      return destroys.get();
    }
  }

  /**
   * cacheWriter to slow down P2P operations, listener only works for c/s in this case
   */
  private static class ThrowingCacheWriter<K, V> extends CacheWriterAdapter<K, V> {

    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger destroys = new AtomicInteger();

    private final CacheListenerAction<Integer> operationCountAction;
    private final long sleepMillis;

    ThrowingCacheWriter(CacheListenerAction<Integer> operationCountAction) {
      this(operationCountAction, 50);
    }

    private ThrowingCacheWriter(CacheListenerAction<Integer> operationCountAction,
        long sleepMillis) {
      this.operationCountAction = operationCountAction;
      this.sleepMillis = sleepMillis;
    }

    @Override
    public synchronized void beforeCreate(EntryEvent<K, V> event) {
      operationCountAction.run(Operation.CREATE, creates.get());
      sleep(sleepMillis);
      creates.incrementAndGet();
    }

    @Override
    public void beforeUpdate(EntryEvent<K, V> event) {
      sleep(sleepMillis);
    }

    @Override
    public void beforeDestroy(EntryEvent<K, V> event) {
      operationCountAction.run(Operation.DESTROY, destroys.get());
      sleep(sleepMillis);
      destroys.incrementAndGet();
    }
  }
}
