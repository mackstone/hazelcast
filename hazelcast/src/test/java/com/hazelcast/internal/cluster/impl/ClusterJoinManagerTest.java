package com.hazelcast.internal.cluster.impl;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.hotrestart.InternalHotRestartService;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.NodeExtension;
import com.hazelcast.logging.ILogger;
import com.hazelcast.logging.Logger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Connection;
import com.hazelcast.nio.Packet;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import com.hazelcast.version.MemberVersion;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Collections.singleton;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class ClusterJoinManagerTest extends HazelcastTestSupport {

    private final ILogger logger = Logger.getLogger(getClass());
    private final Lock lock = new ReentrantLock();

    private Connection connection = mock(Connection.class);
    private InternalHotRestartService hotRestartService = mock(InternalHotRestartService.class);

    private TestHazelcastInstanceFactory factory;

    private ClusterJoinManager manager;
    private ConfigCheck configCheck;
    private String masterUuid;
    private Address joinAddress;

    @Before
    public void setUp() throws Exception {
        factory = createHazelcastInstanceFactory();

        // we get a real Node, NodeEngine and NodeExtension from a Hazelcast instance
        HazelcastInstance hz = factory.newHazelcastInstance();
        Node realNode = getNode(hz);
        NodeExtension realNodeNodeExtension = realNode.getNodeExtension();

        // we inject a mocked HotRestartService into the real NodeExtension
        NodeExtension nodeExtension = spy(realNodeNodeExtension);
        when(nodeExtension.getInternalHotRestartService()).thenReturn(hotRestartService);
        Node node = spy(realNode);
        when(node.getNodeExtension()).thenReturn(nodeExtension);

        manager = new ClusterJoinManager(node, node.getClusterService(), lock);
        configCheck = node.createConfigCheck();
        masterUuid = hz.getCluster().getLocalMember().getUuid();
        joinAddress = new Address("127.0.0.1", 1234);
    }

    @Test
    public void testHandleJoinRequest() {
        JoinRequest request = new JoinRequest(Packet.VERSION, 0, MemberVersion.UNKNOWN, joinAddress, "anyUuid", false,
                configCheck, null, Collections.<String, Object>emptyMap(), Collections.<String>emptySet());

        manager.handleJoinRequest(request, connection);

        verify(hotRestartService).getExcludedMemberUuids();
        verifyNoMoreInteractions(hotRestartService);
    }

    @Test
    public void testHandleJoinRequest_whenLocalUuidIsExcluded_thenHandleExcludedMemberUuids() {
        Set<String> excludeUuid = singleton(masterUuid);

        // we use the real address, but a faked UUID to have the operation sent to our second node
        JoinRequest joinRequest = new JoinRequest(Packet.VERSION, 0, MemberVersion.UNKNOWN, joinAddress, "anyUuid", false,
                configCheck, null, Collections.<String, Object>emptyMap(), excludeUuid);

        manager.handleJoinRequest(joinRequest, connection);

        verify(hotRestartService).getExcludedMemberUuids();
        verify(hotRestartService).handleExcludedMemberUuids(eq(joinAddress), eq(excludeUuid));
        verifyNoMoreInteractions(hotRestartService);
    }

    @Test
    public void testHandleJoinRequest_whenRemoteUuidIsExcluded_thenSendExcludedMemberUuidsOperation() {
        Set<String> excludedUuid = singleton("excludedUuid");
        when(hotRestartService.getExcludedMemberUuids()).thenReturn(excludedUuid);

        // we create another Hazelcast instance, so we have a real target to send an operation to
        HazelcastInstance hz = factory.newHazelcastInstance();
        Address address = getAddress(hz);

        // we use the real address, but a faked UUID to have the operation sent to our second node
        JoinRequest joinRequest = new JoinRequest(Packet.VERSION, 0, MemberVersion.UNKNOWN, address, "excludedUuid", false,
                configCheck, null, Collections.<String, Object>emptyMap(), Collections.<String>emptySet());

        manager.handleJoinRequest(joinRequest, connection);

        // TODO: find a way to verify that the SendExcludedMemberUuidsOperation has been sent

        verify(hotRestartService).getExcludedMemberUuids();
        verifyNoMoreInteractions(hotRestartService);
    }
}