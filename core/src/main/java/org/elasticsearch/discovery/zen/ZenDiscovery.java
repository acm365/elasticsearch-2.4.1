/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.discovery.zen;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.BasicClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateNonMasterUpdateTask;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.NotMasterException;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingService;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.service.InternalClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.discovery.InitialStateDiscoveryListener;
import org.elasticsearch.discovery.zen.elect.ElectMasterService;
import org.elasticsearch.discovery.zen.fd.MasterFaultDetection;
import org.elasticsearch.discovery.zen.fd.NodesFaultDetection;
import org.elasticsearch.discovery.zen.membership.MembershipAction;
import org.elasticsearch.discovery.zen.ping.PingContextProvider;
import org.elasticsearch.discovery.zen.ping.ZenPing;
import org.elasticsearch.discovery.zen.ping.ZenPingService;
import org.elasticsearch.discovery.zen.publish.PublishClusterStateAction;
import org.elasticsearch.node.service.NodeService;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.EmptyTransportResponseHandler;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;

/**
 *
 */
public class ZenDiscovery extends AbstractLifecycleComponent<Discovery> implements Discovery, PingContextProvider {

    public final static String SETTING_PING_TIMEOUT = "discovery.zen.ping.timeout";
    public final static String SETTING_JOIN_TIMEOUT = "discovery.zen.join_timeout";
    public final static String SETTING_JOIN_RETRY_ATTEMPTS = "discovery.zen.join_retry_attempts";
    public final static String SETTING_JOIN_RETRY_DELAY = "discovery.zen.join_retry_delay";
    public final static String SETTING_MAX_PINGS_FROM_ANOTHER_MASTER = "discovery.zen.max_pings_from_another_master";
    public final static String SETTING_SEND_LEAVE_REQUEST = "discovery.zen.send_leave_request";
    public final static String SETTING_MASTER_ELECTION_FILTER_CLIENT = "discovery.zen.master_election.filter_client";
    public final static String SETTING_MASTER_ELECTION_WAIT_FOR_JOINS_TIMEOUT = "discovery.zen.master_election.wait_for_joins_timeout";
    public final static String SETTING_MASTER_ELECTION_FILTER_DATA = "discovery.zen.master_election.filter_data";

    public static final String DISCOVERY_REJOIN_ACTION_NAME = "internal:discovery/zen/rejoin";

    private final TransportService transportService;
    private final ClusterService clusterService;
    private RoutingService routingService;
    private final ClusterName clusterName;
    private final DiscoverySettings discoverySettings;
    private final ZenPingService pingService;

    /**
     * 集群中其他节点ping主节点，
     * 经过discovery.zen.fd.ping_retries次重试，均超时（discovery.zen.fd.ping_timeout），
     * 则认为主节点“失联”，然后发起选举。选举的需要在discovery.zen.ping_timeout时间内完成
     */
    /** 所有节点都会运行 */
    private final MasterFaultDetection masterFD;
    /** 只在master节点上运行故障检测  <<检测脑裂>> */
    private final NodesFaultDetection nodesFD;
    private final PublishClusterStateAction publishClusterState;
    private final MembershipAction membership;

    private final TimeValue pingTimeout;
    private final TimeValue joinTimeout;

    /** how many retry attempts to perform if join request failed with an retriable error */
    private final int joinRetryAttempts;
    /** how long to wait before performing another join attempt after a join request failed with an retriable error */
    private final TimeValue joinRetryDelay;

    /** 在本地主节点或别的主节点，强制重新加入前允许别的master可以ping多少次 */
    /** how many pings from *another* master to tolerate before forcing a rejoin on other or local master */
    private final int maxPingsFromAnotherMaster;

    // a flag that should be used only for testing
    private final boolean sendLeaveRequest;

    private final ElectMasterService electMaster;

    private final boolean masterElectionFilterClientNodes;
    private final boolean masterElectionFilterDataNodes;
    private final TimeValue masterElectionWaitForJoinsTimeout;

    private final CopyOnWriteArrayList<InitialStateDiscoveryListener> initialStateListeners = new CopyOnWriteArrayList<>();

    private final JoinThreadControl joinThreadControl;

    private final AtomicBoolean initialStateSent = new AtomicBoolean();

    /** counts the time this node has joined the cluster or have elected it self as master */
    private final AtomicLong clusterJoinsCounter = new AtomicLong();

    @Nullable
    private NodeService nodeService;

    // must initialized in doStart(), when we have the routingService set
    private volatile NodeJoinController nodeJoinController;
    private volatile NodeRemovalClusterStateTaskExecutor nodeRemovalExecutor;

    @Inject
    public ZenDiscovery(Settings settings, ClusterName clusterName, ThreadPool threadPool,
                        TransportService transportService, final ClusterService clusterService, NodeSettingsService nodeSettingsService,
                        ZenPingService pingService, ElectMasterService electMasterService,
                        DiscoverySettings discoverySettings) {
        super(settings);
        this.clusterName = clusterName;
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.discoverySettings = discoverySettings;
        this.pingService = pingService;
        this.electMaster = electMasterService;
        TimeValue pingTimeout = this.settings.getAsTime("discovery.zen.initial_ping_timeout", timeValueSeconds(3));
        pingTimeout = this.settings.getAsTime("discovery.zen.ping_timeout", pingTimeout);
        pingTimeout = settings.getAsTime("discovery.zen.ping_timeout", pingTimeout);
        this.pingTimeout = settings.getAsTime(SETTING_PING_TIMEOUT, pingTimeout);

        this.joinTimeout = settings.getAsTime(SETTING_JOIN_TIMEOUT, TimeValue.timeValueMillis(this.pingTimeout.millis() * 20));
        this.joinRetryAttempts = settings.getAsInt(SETTING_JOIN_RETRY_ATTEMPTS, 3);
        this.joinRetryDelay = settings.getAsTime(SETTING_JOIN_RETRY_DELAY, TimeValue.timeValueMillis(100));
        this.maxPingsFromAnotherMaster = settings.getAsInt(SETTING_MAX_PINGS_FROM_ANOTHER_MASTER, 3);
        this.sendLeaveRequest = settings.getAsBoolean(SETTING_SEND_LEAVE_REQUEST, true);

        this.masterElectionFilterClientNodes = settings.getAsBoolean(SETTING_MASTER_ELECTION_FILTER_CLIENT, true);
        this.masterElectionFilterDataNodes = settings.getAsBoolean(SETTING_MASTER_ELECTION_FILTER_DATA, false);
        this.masterElectionWaitForJoinsTimeout = settings.getAsTime(SETTING_MASTER_ELECTION_WAIT_FOR_JOINS_TIMEOUT, TimeValue.timeValueMillis(joinTimeout.millis() / 2));

        if (this.joinRetryAttempts < 1) {
            throw new IllegalArgumentException("'" + SETTING_JOIN_RETRY_ATTEMPTS + "' must be a positive number. got [" + SETTING_JOIN_RETRY_ATTEMPTS + "]");
        }
        if (this.maxPingsFromAnotherMaster < 1) {
            throw new IllegalArgumentException("'" + SETTING_MAX_PINGS_FROM_ANOTHER_MASTER + "' must be a positive number. got [" + this.maxPingsFromAnotherMaster + "]");
        }

        logger.debug("using ping.timeout [{}], join.timeout [{}], master_election.filter_client [{}], master_election.filter_data [{}]", pingTimeout, joinTimeout, masterElectionFilterClientNodes, masterElectionFilterDataNodes);

        nodeSettingsService.addListener(new ApplySettings());

        this.masterFD = new MasterFaultDetection(settings, threadPool, transportService, clusterName, clusterService);
        this.masterFD.addListener(new MasterNodeFailureListener());

        this.nodesFD = new NodesFaultDetection(settings, threadPool, transportService, clusterName);
        this.nodesFD.addListener(new NodeFaultDetectionListener());

        this.publishClusterState = new PublishClusterStateAction(settings, transportService, this, new NewClusterStateListener(), discoverySettings);
        this.pingService.setPingContextProvider(this);
        this.membership = new MembershipAction(settings, clusterService, transportService, this, new MembershipListener());

        this.joinThreadControl = new JoinThreadControl(threadPool);

        transportService.registerRequestHandler(DISCOVERY_REJOIN_ACTION_NAME, RejoinClusterRequest.class, ThreadPool.Names.SAME, new RejoinClusterRequestHandler());
    }

    @Override
    public void setNodeService(@Nullable NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @Override
    public void setRoutingService(RoutingService routingService) {
        this.routingService = routingService;
    }

    @Override
    protected void doStart() {
        nodesFD.setLocalNode(clusterService.localNode());
        joinThreadControl.start();
        pingService.start();
        this.nodeJoinController = new NodeJoinController(clusterService, routingService, discoverySettings, settings);
        this.nodeRemovalExecutor = new NodeRemovalClusterStateTaskExecutor(routingService.getAllocationService(), electMaster, new NodeRemovalClusterStateTaskExecutor.Rejoin() {
            @Override
            public ClusterState apply(ClusterState clusterState, String reason) {
                return ZenDiscovery.this.rejoin(clusterState, reason);
            }
        }, logger);
    }

    @Override
    public void startInitialJoin() {
        // start the join thread from a cluster state update. See {@link JoinThreadControl} for details.
        clusterService.submitStateUpdateTask("initial_join", new ClusterStateNonMasterUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                // do the join on a different thread, the DiscoveryService waits for 30s anyhow till it is discovered
                joinThreadControl.startNewThreadIfNotRunning();
                return currentState;
            }

            @Override
            public void onFailure(String source, @org.elasticsearch.common.Nullable Throwable t) {
                logger.warn("failed to start initial join process", t);
            }
        });
    }

    @Override
    protected void doStop() {
        joinThreadControl.stop();
        pingService.stop();
        masterFD.stop("zen disco stop");
        nodesFD.stop();
        initialStateSent.set(false);
        DiscoveryNodes nodes = nodes();
        if (sendLeaveRequest) {
            if (nodes.masterNode() == null) {
                // if we don't know who the master is, nothing to do here
            } else if (!nodes.localNodeMaster()) {
                try {
                    membership.sendLeaveRequestBlocking(nodes.masterNode(), nodes.localNode(), TimeValue.timeValueSeconds(1));
                } catch (Exception e) {
                    logger.debug("failed to send leave request to master [{}]", e, nodes.masterNode());
                }
            } else {
                // we're master -> let other potential master we left and start a master election now rather then wait for masterFD
                DiscoveryNode[] possibleMasters = electMaster.nextPossibleMasters(nodes.nodes().values(), 5);
                for (DiscoveryNode possibleMaster : possibleMasters) {
                    if (nodes.localNode().equals(possibleMaster)) {
                        continue;
                    }
                    try {
                        membership.sendLeaveRequest(nodes.localNode(), possibleMaster);
                    } catch (Exception e) {
                        logger.debug("failed to send leave request from master [{}] to possible master [{}]", e, nodes.masterNode(), possibleMaster);
                    }
                }
            }
        }
    }

    @Override
    protected void doClose() {
        masterFD.close();
        nodesFD.close();
        publishClusterState.close();
        membership.close();
        pingService.close();
    }

    @Override
    public DiscoveryNode localNode() {
        return clusterService.localNode();
    }

    @Override
    public void addListener(InitialStateDiscoveryListener listener) {
        this.initialStateListeners.add(listener);
    }

    @Override
    public void removeListener(InitialStateDiscoveryListener listener) {
        this.initialStateListeners.remove(listener);
    }

    @Override
    public String nodeDescription() {
        return clusterName.value() + "/" + clusterService.localNode().id();
    }

    /** start of {@link org.elasticsearch.discovery.zen.ping.PingContextProvider } implementation */
    @Override
    public DiscoveryNodes nodes() {
        return clusterService.state().nodes();
    }

    @Override
    public NodeService nodeService() {
        return this.nodeService;
    }

    @Override
    public boolean nodeHasJoinedClusterOnce() {
        return clusterJoinsCounter.get() > 0;
    }

    /** end of {@link org.elasticsearch.discovery.zen.ping.PingContextProvider } implementation */


    @Override
    public void publish(ClusterChangedEvent clusterChangedEvent, AckListener ackListener) {
        if (!clusterChangedEvent.state().getNodes().localNodeMaster()) {
            throw new IllegalStateException("Shouldn't publish state when not master");
        }
        nodesFD.updateNodesAndPing(clusterChangedEvent.state());
        publishClusterState.publish(clusterChangedEvent, ackListener);
    }

    /**
     * returns true if zen discovery is started and there is a currently a background thread active for (re)joining
     * the cluster used for testing.
     */
    public boolean joiningCluster() {
        return joinThreadControl.joinThreadActive();
    }

    /**
     * the main function of a join thread. This function is guaranteed to join the cluster
     * or spawn a new join thread upon failure to do so.
     */
    private void innerJoinCluster() {
        DiscoveryNode masterNode = null;
        final Thread currentThread = Thread.currentThread();
        nodeJoinController.startAccumulatingJoins();
        while (masterNode == null && joinThreadControl.joinThreadActive(currentThread)) {
            masterNode = findMaster();
        }

        if (!joinThreadControl.joinThreadActive(currentThread)) {
            logger.trace("thread is no longer in currentJoinThread. Stopping.");
            return;
        }

        // TODO 如果本地节点是主节点，则启动线程池名为SAME的线程去执行ping操作，判断集群中的节点是否正常，如果不正常则移除出集群
        if (clusterService.localNode().equals(masterNode)) {
            // 需要等待minimummasternodes个后才开始进行检测操作
            final int requiredJoins = Math.max(0, electMaster.minimumMasterNodes() - 1); // we count as one
            logger.debug("elected as master, waiting for incoming joins ([{}] needed)", requiredJoins);
            nodeJoinController.waitToBeElectedAsMaster(requiredJoins, masterElectionWaitForJoinsTimeout,
                    new NodeJoinController.ElectionCallback() {
                        @Override
                        public void onElectedAsMaster(ClusterState state) {
                            joinThreadControl.markThreadAsDone(currentThread);
                            // we only starts nodesFD if we are master (it may be that we received a cluster state while pinging)
                            nodesFD.updateNodesAndPing(state); // start the nodes FD
                            sendInitialStateEventIfNeeded();
                            long count = clusterJoinsCounter.incrementAndGet();
                            logger.trace("cluster joins counter set to [{}] (elected as master)", count);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            // 加入集群失败时，重新调用innerJoinCluster方法
                            logger.trace("failed while waiting for nodes to join, rejoining", t);
                            joinThreadControl.markThreadAsDoneAndStartNew(currentThread);
                        }
                    }

            );
        } else {
            // TODO 如果本地节点不是主节点，会向master发送一个join的request
            // 本地节点不是主节点时不会开启故障检测 master fault detection

            // process any incoming joins (they will fail because we are not the master)
            nodeJoinController.stopAccumulatingJoins("not master");

            // send join request
            final boolean success = joinElectedMaster(masterNode);

            // finalize join through the cluster state update thread
            final DiscoveryNode finalMasterNode = masterNode;
            clusterService.submitStateUpdateTask("finalize_join (" + masterNode + ")", new ClusterStateNonMasterUpdateTask() {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    if (!success) {
                        // failed to join. Try again...
                        joinThreadControl.markThreadAsDoneAndStartNew(currentThread);
                        return currentState;
                    }

                    if (currentState.getNodes().masterNode() == null) {
                        // Post 1.3.0, the master should publish a new cluster state before acking our join request. we now should have
                        // a valid master.
                        logger.debug("no master node is set, despite of join request completing. retrying pings.");
                        joinThreadControl.markThreadAsDoneAndStartNew(currentThread);
                        return currentState;
                    }

                    if (!currentState.getNodes().masterNode().equals(finalMasterNode)) {
                        return joinThreadControl.stopRunningThreadAndRejoin(currentState, "master_switched_while_finalizing_join");
                    }

                    // Note: we do not have to start master fault detection here because it's set at {@link #handleNewClusterStateFromMaster }
                    // when the first cluster state arrives.
                    joinThreadControl.markThreadAsDone(currentThread);
                    return currentState;
                }

                @Override
                public void onFailure(String source, @Nullable Throwable t) {
                    logger.error("unexpected error while trying to finalize cluster join", t);
                    joinThreadControl.markThreadAsDoneAndStartNew(currentThread);
                }
            });
        }
    }

    /**
     * Join a newly elected master.
     *
     * @return true if successful
     */
    private boolean joinElectedMaster(DiscoveryNode masterNode) {
        try {
            // first, make sure we can connect to the master
            transportService.connectToNode(masterNode);
        } catch (Exception e) {
            logger.warn("failed to connect to master [{}], retrying...", e, masterNode);
            return false;
        }
        int joinAttempt = 0; // we retry on illegal state if the master is not yet ready
        while (true) {
            try {
                logger.trace("joining master {}", masterNode);
                membership.sendJoinRequestBlocking(masterNode, clusterService.localNode(), joinTimeout);
                return true;
            } catch (Throwable t) {
                Throwable unwrap = ExceptionsHelper.unwrapCause(t);
                if (unwrap instanceof NotMasterException) {
                    if (++joinAttempt == this.joinRetryAttempts) {
                        logger.info("failed to send join request to master [{}], reason [{}], tried [{}] times", masterNode, ExceptionsHelper.detailedMessage(t), joinAttempt);
                        return false;
                    } else {
                        logger.trace("master {} failed with [{}]. retrying... (attempts done: [{}])", masterNode, ExceptionsHelper.detailedMessage(t), joinAttempt);
                    }
                } else {
                    if (logger.isTraceEnabled()) {
                        logger.trace("failed to send join request to master [{}]", t, masterNode);
                    } else {
                        logger.info("failed to send join request to master [{}], reason [{}]", masterNode, ExceptionsHelper.detailedMessage(t));
                    }
                    return false;
                }
            }

            try {
                Thread.sleep(this.joinRetryDelay.millis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // visible for testing
    static class NodeRemovalClusterStateTaskExecutor extends ClusterStateTaskExecutor<NodeRemovalClusterStateTaskExecutor.Task> implements ClusterStateTaskListener {

        private final AllocationService allocationService;
        private final ElectMasterService electMasterService;
        private final Rejoin rejoin;
        private final ESLogger logger;

        static class Task {

            private final DiscoveryNode node;
            private final String reason;

            public Task(final DiscoveryNode node, final String reason) {
                this.node = node;
                this.reason = reason;
            }

            public DiscoveryNode node() {
                return node;
            }

            public String reason() {
                return reason;
            }

            @Override
            public String toString() {
                return node + " " + reason;
            }

        }

        interface Rejoin {
            ClusterState apply(ClusterState clusterState, String reason);
        }

        NodeRemovalClusterStateTaskExecutor(
                final AllocationService allocationService,
                final ElectMasterService electMasterService,
                final Rejoin rejoin,
                final ESLogger logger) {
            this.allocationService = allocationService;
            this.electMasterService = electMasterService;
            this.rejoin = rejoin;
            this.logger = logger;
        }

        @Override
        public BatchResult<Task> execute(final ClusterState currentState, final List<Task> tasks) throws Exception {
            final DiscoveryNodes.Builder remainingNodesBuilder = DiscoveryNodes.builder(currentState.nodes());
            boolean removed = false;
            for (final Task task : tasks) {
                if (currentState.nodes().nodeExists(task.node().getId())) {
                    remainingNodesBuilder.remove(task.node().getId());
                    removed = true;
                } else {
                    logger.debug("node [{}] does not exist in cluster state, ignoring", task);
                }
            }

            if (!removed) {
                // no nodes to remove, keep the current cluster state
                return BatchResult.<Task>builder().successes(tasks).build(currentState);
            }

            final ClusterState remainingNodesClusterState = remainingNodesClusterState(currentState, remainingNodesBuilder);

            final BatchResult.Builder<Task> resultBuilder = BatchResult.<Task>builder().successes(tasks);
            if (!electMasterService.hasEnoughMasterNodes(remainingNodesClusterState.nodes())) {
                return resultBuilder.build(rejoin.apply(remainingNodesClusterState, "not enough master nodes"));
            } else {
                final RoutingAllocation.Result routingResult = allocationService.reroute(remainingNodesClusterState, Joiner.on(",").join(tasks));
                return resultBuilder.build(ClusterState.builder(remainingNodesClusterState).routingResult(routingResult).build());
            }
        }

        // visible for testing
        // hook is used in testing to ensure that correct cluster state is used to test whether a
        // rejoin or reroute is needed
        ClusterState remainingNodesClusterState(final ClusterState currentState, DiscoveryNodes.Builder remainingNodesBuilder) {
            return ClusterState.builder(currentState).nodes(remainingNodesBuilder).build();
        }

        @Override
        public void onFailure(String source, Throwable t) {
            logger.error("unexpected failure during [{}]", t, source);
        }

        @Override
        public void onNoLongerMaster(String source) {
            logger.debug("no longer master while processing node removal [{}]", source);
        }

        @Override
        public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
        }

    }

    private void removeNode(final DiscoveryNode node, final String source, final String reason) {
        clusterService.submitStateUpdateTask(
                source + "(" + node + "), reason(" + reason + ")",
                new NodeRemovalClusterStateTaskExecutor.Task(node, reason),
                BasicClusterStateTaskConfig.create(Priority.IMMEDIATE),
                nodeRemovalExecutor,
                nodeRemovalExecutor);
    }

    private void handleLeaveRequest(final DiscoveryNode node) {
        if (lifecycleState() != Lifecycle.State.STARTED) {
            // not started, ignore a node failure
            return;
        }
        if (localNodeMaster()) {
            removeNode(node, "zen-disco-node-left", "left");
        } else if (node.equals(nodes().masterNode())) {
            handleMasterGone(node, "shut_down");
        }
    }

    private void handleNodeFailure(final DiscoveryNode node, String reason) {
        if (lifecycleState() != Lifecycle.State.STARTED) {
            // not started, ignore a node failure
            return;
        }
        if (!localNodeMaster()) {
            // nothing to do here...
            return;
        }
        removeNode(node, "zen-disco-node-failed", reason);
    }

    private void handleMinimumMasterNodesChanged(final int minimumMasterNodes) {
        if (lifecycleState() != Lifecycle.State.STARTED) {
            // not started, ignore a node failure
            return;
        }
        final int prevMinimumMasterNode = ZenDiscovery.this.electMaster.minimumMasterNodes();
        ZenDiscovery.this.electMaster.minimumMasterNodes(minimumMasterNodes);
        if (!localNodeMaster()) {
            // We only set the new value. If the master doesn't see enough nodes it will revoke it's mastership.
            return;
        }
        clusterService.submitStateUpdateTask("zen-disco-minimum_master_nodes_changed", new ClusterStateUpdateTask(Priority.IMMEDIATE) {
            @Override
            public ClusterState execute(ClusterState currentState) {
                // check if we have enough master nodes, if not, we need to move into joining the cluster again
                if (!electMaster.hasEnoughMasterNodes(currentState.nodes())) {
                    return rejoin(currentState, "not enough master nodes on change of minimum_master_nodes from [" + prevMinimumMasterNode + "] to [" + minimumMasterNodes + "]");
                }
                return currentState;
            }


            @Override
            public void onNoLongerMaster(String source) {
                // ignoring (already logged)
            }

            @Override
            public void onFailure(String source, Throwable t) {
                logger.error("unexpected failure during [{}]", t, source);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                sendInitialStateEventIfNeeded();
            }
        });
    }

    private void handleMasterGone(final DiscoveryNode masterNode, final String reason) {
        if (lifecycleState() != Lifecycle.State.STARTED) {
            // not started, ignore a master failure
            return;
        }
        if (localNodeMaster()) {
            // we might get this on both a master telling us shutting down, and then the disconnect failure
            return;
        }

        logger.info("master_left [{}], reason [{}]", masterNode, reason);

        clusterService.submitStateUpdateTask("zen-disco-master_failed (" + masterNode + ")", new ClusterStateUpdateTask(Priority.IMMEDIATE) {
            @Override
            public boolean runOnlyOnMaster() {
                return false;
            }

            @Override
            public ClusterState execute(ClusterState currentState) {
                if (!masterNode.id().equals(currentState.nodes().masterNodeId())) {
                    // master got switched on us, no need to send anything
                    return currentState;
                }

                DiscoveryNodes discoveryNodes = DiscoveryNodes.builder(currentState.nodes())
                        // make sure the old master node, which has failed, is not part of the nodes we publish
                        .remove(masterNode.id())
                        .masterNodeId(null).build();

                // flush any pending cluster states from old master, so it will not be set as master again
                ArrayList<ProcessClusterState> pendingNewClusterStates = new ArrayList<>();
                processNewClusterStates.drainTo(pendingNewClusterStates);
                logger.trace("removed [{}] pending cluster states", pendingNewClusterStates.size());

                return rejoin(ClusterState.builder(currentState).nodes(discoveryNodes).build(), "master left (reason = " + reason + ")");
            }

            @Override
            public void onFailure(String source, Throwable t) {
                logger.error("unexpected failure during [{}]", t, source);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                sendInitialStateEventIfNeeded();
            }

        });
    }

    static class ProcessClusterState {
        final ClusterState clusterState;
        volatile boolean processed;

        ProcessClusterState(ClusterState clusterState) {
            this.clusterState = clusterState;
        }
    }

    private final BlockingQueue<ProcessClusterState> processNewClusterStates = ConcurrentCollections.newBlockingQueue();

    void handleNewClusterStateFromMaster(ClusterState newClusterState, final PublishClusterStateAction.NewClusterStateListener.NewStateProcessed newStateProcessed) {
        final ClusterName incomingClusterName = newClusterState.getClusterName();
        /* The cluster name can still be null if the state comes from a node that is prev 1.1.1*/
        if (incomingClusterName != null && !incomingClusterName.equals(this.clusterName)) {
            logger.warn("received cluster state from [{}] which is also master but with a different cluster name [{}]", newClusterState.nodes().masterNode(), incomingClusterName);
            newStateProcessed.onNewClusterStateFailed(new IllegalStateException("received state from a node that is not part of the cluster"));
            return;
        }
        if (localNodeMaster()) {
            logger.debug("received cluster state from [{}] which is also master with cluster name [{}]", newClusterState.nodes().masterNode(), incomingClusterName);
            final ClusterState newState = newClusterState;
            clusterService.submitStateUpdateTask("zen-disco-master_receive_cluster_state_from_another_master [" + newState.nodes().masterNode() + "]", new ClusterStateUpdateTask(Priority.URGENT) {
                @Override
                public ClusterState execute(ClusterState currentState) {
                    return handleAnotherMaster(currentState, newState.nodes().masterNode(), newState.version(), "via a new cluster state");
                }

                @Override
                public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                    newStateProcessed.onNewClusterStateProcessed();
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    logger.error("unexpected failure during [{}]", t, source);
                    newStateProcessed.onNewClusterStateFailed(t);
                }

            });
        } else {
            if (newClusterState.nodes().localNode() == null) {
                logger.warn("received a cluster state from [{}] and not part of the cluster, should not happen", newClusterState.nodes().masterNode());
                newStateProcessed.onNewClusterStateFailed(new IllegalStateException("received state from a node that is not part of the cluster"));
            } else {

                final ProcessClusterState processClusterState = new ProcessClusterState(newClusterState);
                processNewClusterStates.add(processClusterState);

                assert newClusterState.nodes().masterNode() != null : "received a cluster state without a master";
                assert !newClusterState.blocks().hasGlobalBlock(discoverySettings.getNoMasterBlock()) : "received a cluster state with a master block";

                clusterService.submitStateUpdateTask("zen-disco-receive(from master [" + newClusterState.nodes().masterNode() + "])", new ClusterStateUpdateTask(Priority.URGENT) {
                    @Override
                    public boolean runOnlyOnMaster() {
                        return false;
                    }

                    @Override
                    public ClusterState execute(ClusterState currentState) {
                        // we already processed it in a previous event
                        if (processClusterState.processed) {
                            return currentState;
                        }

                        // TODO: once improvement that we can do is change the message structure to include version and masterNodeId
                        // at the start, this will allow us to keep the "compressed bytes" around, and only parse the first page
                        // to figure out if we need to use it or not, and only once we picked the latest one, parse the whole state


                        ClusterState updatedState = selectNextStateToProcess(processNewClusterStates);
                        if (updatedState == null) {
                            updatedState = currentState;
                        }
                        if (shouldIgnoreOrRejectNewClusterState(logger, currentState, updatedState)) {
                            return currentState;
                        }

                        // we don't need to do this, since we ping the master, and get notified when it has moved from being a master
                        // because it doesn't have enough master nodes...
                        //if (!electMaster.hasEnoughMasterNodes(newState.nodes())) {
                        //    return disconnectFromCluster(newState, "not enough master nodes on new cluster state wreceived from [" + newState.nodes().masterNode() + "]");
                        //}

                        // check to see that we monitor the correct master of the cluster
                        if (masterFD.masterNode() == null || !masterFD.masterNode().equals(updatedState.nodes().masterNode())) {
                            masterFD.restart(updatedState.nodes().masterNode(), "new cluster state received and we are monitoring the wrong master [" + masterFD.masterNode() + "]");
                        }

                        if (currentState.blocks().hasGlobalBlock(discoverySettings.getNoMasterBlock())) {
                            // its a fresh update from the master as we transition from a start of not having a master to having one
                            logger.debug("got first state from fresh master [{}]", updatedState.nodes().masterNodeId());
                            long count = clusterJoinsCounter.incrementAndGet();
                            logger.trace("updated cluster join cluster to [{}]", count);

                            return updatedState;
                        }


                        // some optimizations to make sure we keep old objects where possible
                        ClusterState.Builder builder = ClusterState.builder(updatedState);

                        // if the routing table did not change, use the original one
                        if (updatedState.routingTable().version() == currentState.routingTable().version()) {
                            builder.routingTable(currentState.routingTable());
                        }
                        // same for metadata
                        if (updatedState.metaData().version() == currentState.metaData().version()) {
                            builder.metaData(currentState.metaData());
                        } else {
                            // if its not the same version, only copy over new indices or ones that changed the version
                            MetaData.Builder metaDataBuilder = MetaData.builder(updatedState.metaData()).removeAllIndices();
                            for (IndexMetaData indexMetaData : updatedState.metaData()) {
                                IndexMetaData currentIndexMetaData = currentState.metaData().index(indexMetaData.getIndex());
                                if (currentIndexMetaData != null && currentIndexMetaData.isSameUUID(indexMetaData.getIndexUUID()) &&
                                        currentIndexMetaData.getVersion() == indexMetaData.getVersion()) {
                                    // safe to reuse
                                    metaDataBuilder.put(currentIndexMetaData, false);
                                } else {
                                    metaDataBuilder.put(indexMetaData, false);
                                }
                            }
                            builder.metaData(metaDataBuilder);
                        }

                        return builder.build();
                    }

                    @Override
                    public void onFailure(String source, Throwable t) {
                        logger.error("unexpected failure during [{}]", t, source);
                        newStateProcessed.onNewClusterStateFailed(t);
                    }

                    @Override
                    public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                        sendInitialStateEventIfNeeded();
                        newStateProcessed.onNewClusterStateProcessed();
                    }
                });
            }
        }
    }

    /**
     * Picks the cluster state with highest version with the same master from the queue. All cluster states with
     * lower versions are ignored. If a cluster state with a different master is seen the processing logic stops and the
     * last processed state is returned.
     */
    static ClusterState selectNextStateToProcess(Queue<ProcessClusterState> processNewClusterStates) {
        // try and get the state with the highest version out of all the ones with the same master node id
        ProcessClusterState stateToProcess = processNewClusterStates.poll();
        if (stateToProcess == null) {
            return null;
        }
        stateToProcess.processed = true;
        while (true) {
            ProcessClusterState potentialState = processNewClusterStates.peek();
            // nothing else in the queue, bail
            if (potentialState == null) {
                break;
            }
            // if its not from the same master, then bail
            if (!Objects.equals(stateToProcess.clusterState.nodes().masterNodeId(), potentialState.clusterState.nodes().masterNodeId())) {
                break;
            }
            // we are going to use it for sure, poll (remove) it
            potentialState = processNewClusterStates.poll();
            if (potentialState == null) {
                // might happen if the queue is drained
                break;
            }
            potentialState.processed = true;

            if (potentialState.clusterState.version() > stateToProcess.clusterState.version()) {
                // we found a new one
                stateToProcess = potentialState;
            }
        }
        return stateToProcess.clusterState;
    }

    /**
     * In the case we follow an elected master the new cluster state needs to have the same elected master and
     * the new cluster state version needs to be equal or higher than our cluster state version.
     * If the first condition fails we reject the cluster state and throw an error.
     * If the second condition fails we ignore the cluster state.
     */
    static boolean shouldIgnoreOrRejectNewClusterState(ESLogger logger, ClusterState currentState, ClusterState newClusterState) {
        if (currentState.nodes().masterNodeId() == null) {
            return false;
        }
        if (!currentState.nodes().masterNodeId().equals(newClusterState.nodes().masterNodeId())) {
            logger.warn("received a cluster state from a different master then the current one, rejecting (received {}, current {})", newClusterState.nodes().masterNode(), currentState.nodes().masterNode());
            throw new IllegalStateException("cluster state from a different master than the current one, rejecting (received " + newClusterState.nodes().masterNode() + ", current " + currentState.nodes().masterNode() + ")");
        } else if (newClusterState.version() < currentState.version()) {
            // if the new state has a smaller version, and it has the same master node, then no need to process it
            logger.debug("received a cluster state that has a lower version than the current one, ignoring (received {}, current {})", newClusterState.version(), currentState.version());
            return true;
        } else {
            return false;
        }
    }

    void handleJoinRequest(final DiscoveryNode node, final ClusterState state, final MembershipAction.JoinCallback callback) {

        if (!transportService.addressSupported(node.address().getClass())) {
            // TODO, what should we do now? Maybe inform that node that its crap?
            logger.warn("received a wrong address type from [{}], ignoring...", node);
        } else if (nodeJoinController == null) {
            throw new IllegalStateException("discovery module is not yet started");
        } else {
            // The minimum supported version for a node joining a master:
            Version minimumNodeJoinVersion = localNode().getVersion().minimumCompatibilityVersion();
            // Sanity check: maybe we don't end up here, because serialization may have failed.
            if (node.getVersion().before(minimumNodeJoinVersion)) {
                callback.onFailure(
                        new IllegalStateException("Can't handle join request from a node with a version [" + node.getVersion() + "] that is lower than the minimum compatible version [" + minimumNodeJoinVersion.minimumCompatibilityVersion() + "]")
                );
                return;
            }

            // try and connect to the node, if it fails, we can raise an exception back to the client...
            transportService.connectToNode(node);

            // validate the join request, will throw a failure if it fails, which will get back to the
            // node calling the join request
            try {
                membership.sendValidateJoinRequestBlocking(node, state, joinTimeout);
            } catch (Throwable e) {
                logger.warn("failed to validate incoming join request from node [{}]", node);
                callback.onFailure(new IllegalStateException("failure when sending a validation request to node", e));
                return;
            }
            nodeJoinController.handleJoinRequest(node, callback);
        }
    }

    private DiscoveryNode findMaster() {

        /**
         *
         * TODO starting to ping
         * 这个过程是找主节点
         * */

        logger.trace("starting to ping");

        /**
         * 取到所有节点信息
         * */
        ZenPing.PingResponse[] fullPingResponses = pingService.pingAndWait(pingTimeout);
        if (fullPingResponses == null) {
            logger.trace("No full ping responses");
            return null;
        }
        if (logger.isTraceEnabled()) {
            StringBuilder sb = new StringBuilder("full ping responses:");
            if (fullPingResponses.length == 0) {
                sb.append(" {none}");
            } else {
                for (ZenPing.PingResponse pingResponse : fullPingResponses) {
                    sb.append("\n\t--> ").append(pingResponse);
                }
            }
            logger.trace(sb.toString());
        }

        // filter responses
        List<ZenPing.PingResponse> pingResponses = new ArrayList<>();
        for (ZenPing.PingResponse pingResponse : fullPingResponses) {
            DiscoveryNode node = pingResponse.node();
            // TODO 过滤节点类型为 clientNode，或设置为 node.master = false && node.data = false，只保留可选举的master节点
            if (masterElectionFilterClientNodes && (node.clientNode() || (!node.masterNode() && !node.dataNode()))) {
                // filter out the client node, which is a client node, or also one that is not data and not master (effectively, client)
            } else if (masterElectionFilterDataNodes && (!node.masterNode() && node.dataNode())) {
                // filter out data node that is not also master
            } else {
                pingResponses.add(pingResponse);
            }
        }

        if (logger.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder("filtered ping responses: (filter_client[").append(masterElectionFilterClientNodes).append("], filter_data[").append(masterElectionFilterDataNodes).append("])");
            if (pingResponses.isEmpty()) {
                sb.append(" {none}");
            } else {
                for (ZenPing.PingResponse pingResponse : pingResponses) {
                    sb.append("\n\t--> ").append(pingResponse);
                }
            }
            logger.debug(sb.toString());
        }

        final DiscoveryNode localNode = clusterService.localNode();
        List<DiscoveryNode> pingMasters = new ArrayList<>();
        for (ZenPing.PingResponse pingResponse : pingResponses) {
            if (pingResponse.master() != null) {
                // TODO 过滤掉本地节点，否则可能无法检查和验证其他节点，2：为了避免每个都将自己作为master而产生脑裂
                // We can't include the local node in pingMasters list, otherwise we may up electing ourselves without
                // any check / verifications from other nodes in ZenDiscover#innerJoinCluster()
                if (!localNode.equals(pingResponse.master())) {
                    pingMasters.add(pingResponse.master());
                }
            }
        }

        // TODO 所有active的node
        // nodes discovered during pinging
        Set<DiscoveryNode> activeNodes = Sets.newHashSet();
        // TODO 集群中之前就存在的node
        // nodes discovered who has previously been part of the cluster and do not ping for the very first time
        Set<DiscoveryNode> joinedOnceActiveNodes = Sets.newHashSet();
        if (localNode.masterNode()) {
            activeNodes.add(localNode);
            long joinsCounter = clusterJoinsCounter.get();
            if (joinsCounter > 0) {
                logger.trace("adding local node to the list of active nodes who has previously joined the cluster (joins counter is [{}})", joinsCounter);
                joinedOnceActiveNodes.add(localNode);
            }
        }
        for (ZenPing.PingResponse pingResponse : pingResponses) {
            activeNodes.add(pingResponse.node());
            if (pingResponse.hasJoinedOnce()) {
                joinedOnceActiveNodes.add(pingResponse.node());
            }
        }
        if (pingMasters.isEmpty()) {
            // TODO 通过ping发现的master为空时，需要重新选举
            // 首先判断activeNodes的master数量是否大于minimumMasterNodes，否则无法选举
            if (electMaster.hasEnoughMasterNodes(activeNodes)) {
                // we give preference to nodes who have previously already joined the cluster. Those will
                // have a cluster state in memory, including an up to date routing table (which is not persistent to disk by the gateway)
                // 首选使用先前加入集群的节点进行选举，因为先前已加入集群的节点有集群状态 （joinedOnceActiveNodes中会把本地节点去掉，因为刚启动）
                DiscoveryNode master = electMaster.electMaster(joinedOnceActiveNodes);
                if (master != null) {
                    return master;
                }
                // 当已加入集群的节点无法选举出master时，在根据activeNodes节点进行选举
                return electMaster.electMaster(activeNodes);
            } else {
                // if we don't have enough master nodes, we bail, because there are not enough master to elect from
                logger.trace("not enough master nodes [{}]", activeNodes);
                return null;
            }
        } else {

            assert !pingMasters.contains(localNode) : "local node should never be elected as master when other nodes indicate an active master";
            // lets tie break between discovered nodes
            // 如果ping发现有master则返回这个master
            return electMaster.electMaster(pingMasters);
        }
    }

    protected ClusterState rejoin(ClusterState clusterState, String reason) {

        // *** called from within an cluster state update task *** //
        assert Thread.currentThread().getName().contains(InternalClusterService.UPDATE_THREAD_NAME);

        logger.warn(reason + ", current nodes: {}", clusterState.nodes());
        nodesFD.stop();
        masterFD.stop(reason);


        ClusterBlocks clusterBlocks = ClusterBlocks.builder().blocks(clusterState.blocks())
                .addGlobalBlock(discoverySettings.getNoMasterBlock())
                .build();

        // clean the nodes, we are now not connected to anybody, since we try and reform the cluster
        DiscoveryNodes discoveryNodes = new DiscoveryNodes.Builder(clusterState.nodes()).masterNodeId(null).build();

        // TODO: do we want to force a new thread if we actively removed the master? this is to give a full pinging cycle
        // before a decision is made.
        joinThreadControl.startNewThreadIfNotRunning();

        return ClusterState.builder(clusterState)
                .blocks(clusterBlocks)
                .nodes(discoveryNodes)
                .build();
    }

    private boolean localNodeMaster() {
        return nodes().localNodeMaster();
    }

    /**
     * TODO 处理收到其他node的信息，说明有两个master，一般称为脑裂，遇到这种情况，node会比较自己的版本和另外master的版本。
     * 如果自己的版本比别人的版本小，就会stop掉自己的nodesFD和masterFD，然后rejoin进集群
     * 如果比别人大，那自己依然是master，然后发送信息给别人，说你是冒充的master，乖乖进入rejoin流程吧
     * @param localClusterState
     * @param otherMaster
     * @param otherClusterStateVersion
     * @param reason
     * @return
     */
    private ClusterState handleAnotherMaster(ClusterState localClusterState, final DiscoveryNode otherMaster, long otherClusterStateVersion, String reason) {
        assert localClusterState.nodes().localNodeMaster() : "handleAnotherMaster called but current node is not a master";
        assert Thread.currentThread().getName().contains(InternalClusterService.UPDATE_THREAD_NAME) : "not called from the cluster state update thread";

        if (otherClusterStateVersion > localClusterState.version()) {
            return rejoin(localClusterState, "zen-disco-discovered another master with a new cluster_state [" + otherMaster + "][" + reason + "]");
        } else {
            logger.warn("discovered [{}] which is also master but with an older cluster_state, telling [{}] to rejoin the cluster ([{}])", otherMaster, otherMaster, reason);
            try {
                // make sure we're connected to this node (connect to node does nothing if we're already connected)
                // since the network connections are asymmetric, it may be that we received a state but have disconnected from the node
                // in the past (after a master failure, for example)
                transportService.connectToNode(otherMaster);
                transportService.sendRequest(otherMaster, DISCOVERY_REJOIN_ACTION_NAME, new RejoinClusterRequest(localClusterState.nodes().localNodeId()), new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {

                    @Override
                    public void handleException(TransportException exp) {
                        logger.warn("failed to send rejoin request to [{}]", exp, otherMaster);
                    }
                });
            } catch (Exception e) {
                logger.warn("failed to send rejoin request to [{}]", e, otherMaster);
            }
            return localClusterState;
        }
    }

    private void sendInitialStateEventIfNeeded() {
        if (initialStateSent.compareAndSet(false, true)) {
            for (InitialStateDiscoveryListener listener : initialStateListeners) {
                listener.initialStateProcessed();
            }
        }
    }

    private class NewClusterStateListener implements PublishClusterStateAction.NewClusterStateListener {

        @Override
        public void onNewClusterState(ClusterState clusterState, NewStateProcessed newStateProcessed) {
            handleNewClusterStateFromMaster(clusterState, newStateProcessed);
        }
    }

    private class MembershipListener implements MembershipAction.MembershipListener {
        @Override
        public void onJoin(DiscoveryNode node, MembershipAction.JoinCallback callback) {
            handleJoinRequest(node, clusterService.state(), callback);
        }

        @Override
        public void onLeave(DiscoveryNode node) {
            handleLeaveRequest(node);
        }
    }

    private class NodeFaultDetectionListener extends NodesFaultDetection.Listener {

        private final AtomicInteger pingsWhileMaster = new AtomicInteger(0);

        @Override
        public void onNodeFailure(DiscoveryNode node, String reason) {
            handleNodeFailure(node, reason);
        }

        @Override
        public void onPingReceived(final NodesFaultDetection.PingRequest pingRequest) {
            // if we are master, we don't expect any fault detection from another node. If we get it
            // means we potentially have two masters in the cluster.
            // TODO 如果收到其他节点的故障检测，说明可能存在脑裂问题了。说明有2个master
            if (!localNodeMaster()) {
                pingsWhileMaster.set(0);
                return;
            }

            // nodes pre 1.4.0 do not send this information
            if (pingRequest.masterNode() == null) {
                return;
            }

            if (pingsWhileMaster.incrementAndGet() < maxPingsFromAnotherMaster) {
                logger.trace("got a ping from another master {}. current ping count: [{}]", pingRequest.masterNode(), pingsWhileMaster.get());
                return;
            }
            // TODO 当超过允许的最大ping次数（maxPingsFromAnotherMaster）就开始处理哪个应该重新加入集群
            logger.debug("got a ping from another master {}. resolving who should rejoin. current ping count: [{}]", pingRequest.masterNode(), pingsWhileMaster.get());
            clusterService.submitStateUpdateTask("ping from another master", new ClusterStateUpdateTask(Priority.IMMEDIATE) {
                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    pingsWhileMaster.set(0);
                    // 怎么处理看handleAnotherMaster注释
                    return handleAnotherMaster(currentState, pingRequest.masterNode(), pingRequest.clusterStateVersion(), "node fd ping");
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    logger.debug("unexpected error during cluster state update task after pings from another master", t);
                }
            });
        }
    }

    private class MasterNodeFailureListener implements MasterFaultDetection.Listener {

        @Override
        public void onMasterFailure(DiscoveryNode masterNode, String reason) {
            handleMasterGone(masterNode, reason);
        }
    }

    public static class RejoinClusterRequest extends TransportRequest {

        private String fromNodeId;

        RejoinClusterRequest(String fromNodeId) {
            this.fromNodeId = fromNodeId;
        }

        public RejoinClusterRequest() {
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            fromNodeId = in.readOptionalString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeOptionalString(fromNodeId);
        }
    }

    /**
     * 加入集群请求处理
     *
     * TODO 这个貌似是主节点在处理？ 不太清楚
     *
     * 接受到消息后，回复一个TransportResponse.Empty消息给node，然后node会回复MasterPingResponseResponse信息
     *
     */
    class RejoinClusterRequestHandler extends TransportRequestHandler<RejoinClusterRequest> {
        @Override
        public void messageReceived(final RejoinClusterRequest request, final TransportChannel channel) throws Exception {
            clusterService.submitStateUpdateTask("received a request to rejoin the cluster from [" + request.fromNodeId + "]", new ClusterStateUpdateTask(Priority.IMMEDIATE) {
                @Override
                public boolean runOnlyOnMaster() {
                    return false;
                }

                @Override
                public ClusterState execute(ClusterState currentState) {
                    try {
                        channel.sendResponse(TransportResponse.Empty.INSTANCE);
                    } catch (Exception e) {
                        logger.warn("failed to send response on rejoin cluster request handling", e);
                    }
                    return rejoin(currentState, "received a request to rejoin the cluster from [" + request.fromNodeId + "]");
                }

                @Override
                public void onNoLongerMaster(String source) {
                    // already logged
                }

                @Override
                public void onFailure(String source, Throwable t) {
                    logger.error("unexpected failure during [{}]", t, source);
                }
            });
        }
    }

    class ApplySettings implements NodeSettingsService.Listener {
        @Override
        public void onRefreshSettings(Settings settings) {
            int minimumMasterNodes = settings.getAsInt(ElectMasterService.DISCOVERY_ZEN_MINIMUM_MASTER_NODES,
                    ZenDiscovery.this.electMaster.minimumMasterNodes());
            if (minimumMasterNodes != ZenDiscovery.this.electMaster.minimumMasterNodes()) {
                logger.info("updating {} from [{}] to [{}]", ElectMasterService.DISCOVERY_ZEN_MINIMUM_MASTER_NODES,
                        ZenDiscovery.this.electMaster.minimumMasterNodes(), minimumMasterNodes);
                handleMinimumMasterNodesChanged(minimumMasterNodes);
            }
        }
    }


    /**
     * All control of the join thread should happen under the cluster state update task thread.
     * This is important to make sure that the background joining process is always in sync with any cluster state updates
     * like master loss, failure to join, received cluster state while joining etc.
     */
    private class JoinThreadControl {

        private final ThreadPool threadPool;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicReference<Thread> currentJoinThread = new AtomicReference<>();

        public JoinThreadControl(ThreadPool threadPool) {
            this.threadPool = threadPool;
        }

        /** returns true if join thread control is started and there is currently an active join thread */
        public boolean joinThreadActive() {
            Thread currentThread = currentJoinThread.get();
            return running.get() && currentThread != null && currentThread.isAlive();
        }

        /** returns true if join thread control is started and the supplied thread is the currently active joinThread */
        public boolean joinThreadActive(Thread joinThread) {
            return running.get() && joinThread.equals(currentJoinThread.get());
        }

        /** cleans any running joining thread and calls {@link #rejoin} */
        public ClusterState stopRunningThreadAndRejoin(ClusterState clusterState, String reason) {
            assertClusterStateThread();
            currentJoinThread.set(null);
            return rejoin(clusterState, reason);
        }

        /** starts a new joining thread if there is no currently active one and join thread controlling is started */
        public void startNewThreadIfNotRunning() {
            assertClusterStateThread();
            if (joinThreadActive()) {
                return;
            }
            threadPool.generic().execute(new Runnable() {
                @Override
                public void run() {
                    Thread currentThread = Thread.currentThread();
                    if (!currentJoinThread.compareAndSet(null, currentThread)) {
                        return;
                    }
                    while (running.get() && joinThreadActive(currentThread)) {
                        try {
                            innerJoinCluster();
                            return;
                        } catch (Exception e) {
                            logger.error("unexpected error while joining cluster, trying again", e);
                            // Because we catch any exception here, we want to know in
                            // tests if an uncaught exception got to this point and the test infra uncaught exception
                            // leak detection can catch this. In practise no uncaught exception should leak
                            assert ExceptionsHelper.reThrowIfNotNull(e);
                        }
                    }
                    // cleaning the current thread from currentJoinThread is done by explicit calls.
                }
            });
        }

        /**
         * marks the given joinThread as completed and makes sure another thread is running (starting one if needed)
         * If the given thread is not the currently running join thread, the command is ignored.
         */
        public void markThreadAsDoneAndStartNew(Thread joinThread) {
            assertClusterStateThread();
            if (!markThreadAsDone(joinThread)) {
                return;
            }
            startNewThreadIfNotRunning();
        }

        /** marks the given joinThread as completed. Returns false if the supplied thread is not the currently active join thread */
        public boolean markThreadAsDone(Thread joinThread) {
            assertClusterStateThread();
            return currentJoinThread.compareAndSet(joinThread, null);
        }

        public void stop() {
            running.set(false);
            Thread joinThread = currentJoinThread.getAndSet(null);
            if (joinThread != null) {
                joinThread.interrupt();
            }
        }

        public void start() {
            running.set(true);
        }

        private void assertClusterStateThread() {
            assert clusterService instanceof InternalClusterService == false || ((InternalClusterService) clusterService).assertClusterStateThread();
        }

    }
}
