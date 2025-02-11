/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.zwave.handler;

import static org.openhab.binding.zwave.ZWaveBindingConstants.*;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.config.core.validation.ConfigValidationException;
import org.eclipse.smarthome.config.discovery.DiscoveryService;
import org.eclipse.smarthome.core.events.Event;
import org.eclipse.smarthome.core.events.EventPublisher;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.UID;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.zwave.ZWaveBindingConstants;
import org.openhab.binding.zwave.discovery.ZWaveDiscoveryService;
import org.openhab.binding.zwave.event.BindingEventDTO;
import org.openhab.binding.zwave.event.BindingEventFactory;
import org.openhab.binding.zwave.event.BindingEventType;
import org.openhab.binding.zwave.internal.ZWaveEventPublisher;
import org.openhab.binding.zwave.internal.protocol.SerialMessage;
import org.openhab.binding.zwave.internal.protocol.ZWaveController;
import org.openhab.binding.zwave.internal.protocol.ZWaveEventListener;
import org.openhab.binding.zwave.internal.protocol.ZWaveIoHandler;
import org.openhab.binding.zwave.internal.protocol.ZWaveNode;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveInclusionEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveInitializationStateEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveNetworkEvent;
import org.openhab.binding.zwave.internal.protocol.event.ZWaveNetworkStateEvent;
import org.openhab.binding.zwave.internal.protocol.serialmessage.RemoveFailedNodeMessageClass.Report;
import org.openhab.binding.zwave.internal.protocol.transaction.ZWaveCommandClassTransactionPayload;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link ZWaveControllerHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Chris Jackson - Initial contribution
 */
public abstract class ZWaveControllerHandler extends BaseBridgeHandler implements ZWaveEventListener, ZWaveIoHandler {

    private final Logger logger = LoggerFactory.getLogger(ZWaveControllerHandler.class);

    private ZWaveDiscoveryService discoveryService;
    private ServiceRegistration discoveryRegistration;

    private volatile ZWaveController controller;

    private Boolean isMaster;
    private Integer sucNode;
    private String networkKey;
    private Integer secureInclusionMode;
    private Integer healTime;
    private Integer wakeupDefaultPeriod;

    private final int SEARCHTIME_MINIMUM = 20;
    private final int SEARCHTIME_DEFAULT = 30;
    private final int SEARCHTIME_MAXIMUM = 300;
    private int searchTime;

    private ScheduledFuture<?> healJob = null;

    public ZWaveControllerHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing ZWave Controller {}.", getThing().getUID());

        Object param;
        param = getConfig().get(CONFIGURATION_MASTER);
        if (param instanceof Boolean) {
            isMaster = (Boolean) param;
        } else {
            isMaster = true;
        }

        param = getConfig().get(CONFIGURATION_SECUREINCLUSION);
        if (param instanceof BigDecimal) {
            secureInclusionMode = ((BigDecimal) param).intValue();
        } else {
            secureInclusionMode = 0;
        }

        param = getConfig().get(CONFIGURATION_INCLUSIONTIMEOUT);
        if (param instanceof BigDecimal) {
            searchTime = ((BigDecimal) param).intValue();
        } else {
            searchTime = SEARCHTIME_DEFAULT;
        }

        if (searchTime < SEARCHTIME_MINIMUM || searchTime > SEARCHTIME_MAXIMUM) {
            searchTime = SEARCHTIME_DEFAULT;
        }

        param = getConfig().get(CONFIGURATION_DEFAULTWAKEUPPERIOD);
        if (param instanceof BigDecimal) {
            wakeupDefaultPeriod = ((BigDecimal) param).intValue();
        } else {
            wakeupDefaultPeriod = 0;
        }

        param = getConfig().get(CONFIGURATION_SISNODE);
        if (param instanceof BigDecimal) {
            sucNode = ((BigDecimal) param).intValue();
        } else {
            sucNode = 0;
        }

        param = getConfig().get(CONFIGURATION_NETWORKKEY);
        if (param instanceof String) {
            networkKey = (String) param;
        }
        if (networkKey.length() == 0) {
            logger.debug("No network key set by user - using random value.");

            // Create random network key
            networkKey = "";
            for (int cnt = 0; cnt < 16; cnt++) {
                int value = (int) Math.floor((Math.random() * 255));
                if (cnt != 0) {
                    networkKey += " ";
                }
                networkKey += String.format("%02X", value);
            }
            // Persist the value
            Configuration configuration = editConfiguration();
            configuration.put(ZWaveBindingConstants.CONFIGURATION_NETWORKKEY, networkKey);
            try {
                // If the thing is defined statically, then this will fail and we will never start!
                updateConfiguration(configuration);
            } catch (IllegalStateException e) {
                // Eat it...
            }
        }

        param = getConfig().get(CONFIGURATION_HEALTIME);
        if (param instanceof BigDecimal) {
            healTime = ((BigDecimal) param).intValue();
        } else {
            healTime = -1;
        }
        initializeHeal();

        // We must set the state
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, ZWaveBindingConstants.OFFLINE_CTLR_OFFLINE);
    }

    /**
     * Common initialisation point for all ZWave controllers.
     * Called by bridges after they have initialised their interfaces.
     *
     */
    protected void initializeNetwork() {
        logger.debug("Initialising ZWave controller");

        // Create config parameters
        Map<String, String> config = new HashMap<String, String>();
        config.put("masterController", isMaster.toString());
        config.put("sucNode", sucNode.toString());
        config.put("secureInclusion", secureInclusionMode.toString());
        config.put("networkKey", networkKey);
        config.put("wakeupDefaultPeriod", wakeupDefaultPeriod.toString());

        // TODO: Handle soft reset?
        controller = new ZWaveController(this, config);
        controller.addEventListener(this);

        // Start the discovery service
        discoveryService = new ZWaveDiscoveryService(this, searchTime);
        discoveryService.activate();

        // And register it as an OSGi service
        discoveryRegistration = bundleContext.registerService(DiscoveryService.class.getName(), discoveryService,
                new Hashtable<String, Object>());
    }

    private void initializeHeal() {
        if (healJob != null) {
            healJob.cancel(true);
            healJob = null;
        }

        if (healTime >= 0 && healTime <= 23) {
            Runnable healRunnable = new Runnable() {
                @Override
                public void run() {
                    if (controller == null) {
                        return;
                    }
                    logger.debug("Starting network mesh heal for controller {}.", getThing().getUID());
                    for (ZWaveNode node : controller.getNodes()) {
                        logger.debug("Starting network mesh heal for controller {}.", getThing().getUID());
                        node.healNode();
                    }
                }
            };

            Calendar cal = Calendar.getInstance();
            int hours = healTime - cal.get(Calendar.HOUR_OF_DAY);
            if (hours < 0) {
                hours += 24;
            }

            logger.debug("Scheduling network mesh heal for {} hours time.", hours);

            healJob = scheduler.scheduleAtFixedRate(healRunnable, hours, 24, TimeUnit.HOURS);
        }
    }

    @Override
    public void dispose() {
        if (healJob != null) {
            healJob.cancel(true);
            healJob = null;
        }

        // Remove the discovery service
        if (discoveryService != null) {
            discoveryService.deactivate();
            discoveryService = null;
        }

        if (discoveryRegistration != null) {
            discoveryRegistration.unregister();
            discoveryRegistration = null;
        }

        ZWaveController controller = this.controller;
        if (controller != null) {
            this.controller = null;
            controller.shutdown();
            controller.removeEventListener(this);
        }
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters)
            throws ConfigValidationException {
        logger.debug("Controller Configuration update received");

        // Perform checking on the configuration
        validateConfigurationParameters(configurationParameters);

        boolean reinitialise = false;

        Configuration configuration = editConfiguration();
        for (Entry<String, Object> configurationParameter : configurationParameters.entrySet()) {
            Object value = configurationParameter.getValue();
            logger.debug("Controller Configuration update {} to {}", configurationParameter.getKey(), value);
            if (value == null) {
                continue;
            }

            String[] cfg = configurationParameter.getKey().split("_");
            if ("controller".equals(cfg[0])) {
                if (controller == null) {
                    logger.debug("Trying to send controller command, but controller is not initialised");
                    continue;
                }

                if (cfg[1].equals("softreset") && value instanceof Boolean && ((Boolean) value) == true) {
                    controller.requestSoftReset();
                    value = false;
                } else if (cfg[1].equals("hardreset") && value instanceof Boolean && ((Boolean) value) == true) {
                    controller.requestHardReset();
                    value = false;
                } else if (cfg[1].equals("exclude") && value instanceof Boolean && ((Boolean) value) == true) {
                    controller.requestRemoveNodesStart();
                    value = false;
                } else if (cfg[1].equals("sync") && value instanceof Boolean && ((Boolean) value) == true) {
                    controller.requestRequestNetworkUpdate();
                    value = false;
                } else if (cfg[1].equals("suc") && value instanceof Boolean) {
                    // TODO: Do we need to set this immediately
                } else if (cfg[1].equals("inclusiontimeout") && value instanceof BigDecimal) {
                    reinitialise = true;
                }
            }
            if ("security".equals(cfg[0])) {
                if (cfg[1].equals("networkkey")) {
                    // Format the key here so it's presented nicely and consistently to the user!
                    String hexString = (String) value;
                    hexString = hexString.replace("0x", "");
                    hexString = hexString.replace(",", "");
                    hexString = hexString.replace(" ", "");
                    hexString = hexString.toUpperCase();
                    if ((hexString.length() % 2) != 0) {
                        hexString += "0";
                    }

                    int arrayLength = (int) Math.ceil(((hexString.length() / 2)));
                    String[] result = new String[arrayLength];

                    int j = 0;
                    StringBuilder builder = new StringBuilder();
                    int lastIndex = result.length - 1;
                    for (int i = 0; i < lastIndex; i++) {
                        builder.append(hexString.substring(j, j + 2) + " ");
                        j += 2;
                    }
                    builder.append(hexString.substring(j));
                    value = builder.toString();

                    reinitialise = true;
                }
            }

            if ("port".equals(cfg[0])) {
                reinitialise = true;
            }

            if ("heal".equals(cfg[0])) {
                healTime = ((BigDecimal) value).intValue();
                initializeHeal();
            }

            configuration.put(configurationParameter.getKey(), value);
        }

        // Persist changes
        updateConfiguration(configuration);

        if (reinitialise == true) {
            dispose();
            initialize();
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    public void startDeviceDiscovery() {
        if (controller == null) {
            return;
        }

        int inclusionMode = 2;
        Object param = getConfig().get(CONFIGURATION_INCLUSION_MODE);
        if (param instanceof BigDecimal) {
            inclusionMode = ((BigDecimal) param).intValue();
        }

        controller.requestAddNodesStart(inclusionMode);
    }

    public void stopDeviceDiscovery() {
        if (controller == null) {
            return;
        }
        controller.requestInclusionStop();
    }

    private void updateControllerProperties() {
        Configuration configuration = editConfiguration();
        configuration.put(ZWaveBindingConstants.CONFIGURATION_SISNODE, controller.getSucId());
        try {
            // If the thing is defined statically, then this will fail and we will never start!
            updateConfiguration(configuration);
        } catch (IllegalStateException e) {
            // Eat it...
        }
    }

    @Override
    public void ZWaveIncomingEvent(ZWaveEvent event) {
        // If this event requires us to let the users know something, then we create a notification
        String eventKey = null;
        BindingEventType eventState = null;
        String eventEntity = null;
        String eventId = null;
        Object eventArgs = null;

        if (event instanceof ZWaveNetworkStateEvent) {
            logger.debug("Controller: Incoming Network State Event {}",
                    ((ZWaveNetworkStateEvent) event).getNetworkState());
            if (((ZWaveNetworkStateEvent) event).getNetworkState() == true) {
                updateStatus(ThingStatus.ONLINE);
                updateControllerProperties();
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE,
                        ZWaveBindingConstants.OFFLINE_CTLR_OFFLINE);
            }
        }

        if (event instanceof ZWaveNetworkEvent) {
            ZWaveNetworkEvent networkEvent = (ZWaveNetworkEvent) event;

            switch (networkEvent.getEvent()) {
                case NodeRoutingInfo:
                    if (networkEvent.getNodeId() == getOwnNodeId()) {
                        updateNeighbours();
                    }
                    break;
                case RemoveFailedNodeID:
                    eventEntity = "network"; // ??
                    eventArgs = new Integer(networkEvent.getNodeId());
                    eventId = ((Report) networkEvent.getValue()).toString();
                    switch ((Report) networkEvent.getValue()) {
                        case FAILED_NODE_NOT_FOUND:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTFOUND;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_PRIMARY_CONTROLLER:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTCTLR;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTREMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NO_CALLBACK_FUNCTION:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOCALLBACK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_OK:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NODEOK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_REMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_FAILED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_PROCESS_BUSY:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_BUSY;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_UNKNOWN_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_UNKNOWN;
                            eventState = BindingEventType.WARNING;
                            break;
                        default:
                            break;
                    }
                    break;
                case ReplaceFailedNode:
                    eventEntity = "network"; // ??
                    eventArgs = new Integer(networkEvent.getNodeId());
                    eventId = ((Report) networkEvent.getValue()).toString();
                    switch ((Report) networkEvent.getValue()) {
                        case FAILED_NODE_NOT_FOUND:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTFOUND;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_PRIMARY_CONTROLLER:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTCTLR;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NOT_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOTREMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_NO_CALLBACK_FUNCTION:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NOCALLBACK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_OK:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_NODEOK;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVED:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_REMOVED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_FAILED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_REMOVE_PROCESS_BUSY:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_BUSY;
                            eventState = BindingEventType.WARNING;
                            break;
                        case FAILED_NODE_UNKNOWN_FAIL:
                            eventKey = ZWaveBindingConstants.EVENT_REMOVEFAILED_UNKNOWN;
                            eventState = BindingEventType.WARNING;
                            break;
                        default:
                            break;
                    }
                    break;
                case RequestNetworkUpdate:
                    eventEntity = "network";

                    switch ((int) networkEvent.getValue()) {
                        case 0: // ZW_SUC_UPDATE_DONE
                            eventId = "ZW_SUC_UPDATE_DONE";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_DONE;
                            eventState = BindingEventType.SUCCESS;
                            break;
                        case 1: // ZW_SUC_UPDATE_ABORT
                            eventId = "ZW_SUC_UPDATE_ABORT";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_ABORT;
                            eventState = BindingEventType.WARNING;
                            break;
                        case 2: // ZW_SUC_UPDATE_WAIT
                            eventId = "ZW_SUC_UPDATE_WAIT";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_WAIT;
                            eventState = BindingEventType.WARNING;
                            break;
                        case 3: // ZW_SUC_UPDATE_DISABLED
                            eventId = "ZW_SUC_UPDATE_DISABLED";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_DISABLED;
                            eventState = BindingEventType.WARNING;
                            break;
                        case 4: // ZW_SUC_UPDATE_OVERFLOW
                            eventId = "ZW_SUC_UPDATE_OVERFLOW";
                            eventKey = ZWaveBindingConstants.EVENT_NETWORKUPDATE_OVERFLOW;
                            eventState = BindingEventType.WARNING;
                            break;
                        default:
                            break;
                    }
                    break;
                default:
                    break;
            }
        }

        // Handle node discover inclusion events
        if (event instanceof ZWaveInclusionEvent) {
            ZWaveInclusionEvent incEvent = (ZWaveInclusionEvent) event;

            eventEntity = "network";
            eventId = incEvent.getEvent().toString();
            switch (incEvent.getEvent()) {
                case IncludeStart:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_STARTED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case IncludeFail:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_FAILED;
                    eventState = BindingEventType.WARNING;
                    break;
                case IncludeDone:
                    // Ignore node 0 - this just indicates inclusion is finished
                    if (incEvent.getNodeId() == 0) {
                        break;
                    }
                    discoveryService.deviceDiscovered(event.getNodeId());
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_COMPLETED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case SecureIncludeComplete:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_SECURECOMPLETED;
                    eventState = BindingEventType.SUCCESS;
                    eventArgs = new Integer(incEvent.getNodeId());
                    break;
                case SecureIncludeFailed:
                    eventKey = ZWaveBindingConstants.EVENT_INCLUSION_SECUREFAILED;
                    eventState = BindingEventType.ERROR;
                    eventArgs = new Integer(incEvent.getNodeId());
                    break;
                case ExcludeStart:
                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_STARTED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case ExcludeFail:
                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_FAILED;
                    eventState = BindingEventType.WARNING;
                    break;
                case ExcludeDone:
                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_COMPLETED;
                    eventState = BindingEventType.SUCCESS;
                    break;
                case ExcludeControllerFound:
                case ExcludeSlaveFound:
                    // Ignore node 0 - this just indicates exclusion finished
                    if (incEvent.getNodeId() == 0) {
                        break;
                    }

                    eventKey = ZWaveBindingConstants.EVENT_EXCLUSION_NODEREMOVED;
                    eventState = BindingEventType.SUCCESS;
                    eventArgs = new Integer(incEvent.getNodeId());
                    break;
                case IncludeControllerFound:
                case IncludeSlaveFound:
                    break;
            }
        }

        if (event instanceof ZWaveInitializationStateEvent) {
            ZWaveInitializationStateEvent initEvent = (ZWaveInitializationStateEvent) event;
            switch (initEvent.getStage()) {
                case DISCOVERY_COMPLETE:
                    // At this point we know enough information about the device to advise the discovery
                    // service that there's a new thing.
                    // We need to do this here as we needed to know the device information such as manufacturer,
                    // type, id and version
                    ZWaveNode node = controller.getNode(initEvent.getNodeId());
                    if (node != null) {
                        deviceAdded(node);
                    }
                default:
                    break;
            }
        }

        if (eventKey != null) {
            EventPublisher ep = ZWaveEventPublisher.getEventPublisher();
            if (ep != null) {
                BindingEventDTO dto = new BindingEventDTO(eventState,
                        BindingEventFactory.formatEvent(eventKey, eventArgs));
                Event notification = BindingEventFactory.createBindingEvent(ZWaveBindingConstants.BINDING_ID,
                        eventEntity, eventId, dto);
                ep.post(notification);
            }
        }
    }

    protected void incomingMessage(SerialMessage serialMessage) {
        if (controller == null) {
            return;
        }
        controller.incomingPacket(serialMessage);
    }

    @Override
    public void deviceDiscovered(int nodeId) {
        if (discoveryService == null) {
            return;
        }
        // discoveryService.deviceDiscovered(nodeId);
    }

    public void deviceAdded(ZWaveNode node) {
        if (discoveryService == null) {
            return;
        }
        discoveryService.deviceAdded(node);
    }

    public int getOwnNodeId() {
        if (controller == null) {
            return 0;
        }
        return controller.getOwnNodeId();
    }

    public ZWaveNode getNode(int node) {
        if (controller == null) {
            return null;
        }

        return controller.getNode(node);
    }

    public Collection<ZWaveNode> getNodes() {
        if (controller == null) {
            return null;
        }
        return controller.getNodes();
    }

    /**
     * Transmits the {@link ZWaveCommandClassTransactionPayload} to a Node.
     * This will not wait for the transaction response.
     *
     * @param transaction the {@link ZWaveCommandClassTransactionPayload} message to send.
     */
    public void sendData(ZWaveCommandClassTransactionPayload transaction) {
        if (controller == null) {
            return;
        }
        controller.sendData(transaction);
    }

    public boolean addEventListener(ZWaveThingHandler zWaveThingHandler) {
        if (controller == null) {
            logger.error("Attempting to add listener when controller is null");
            return false;
        }
        controller.addEventListener(zWaveThingHandler);
        return true;
    }

    public boolean removeEventListener(ZWaveThingHandler zWaveThingHandler) {
        if (controller == null) {
            return false;
        }
        controller.removeEventListener(zWaveThingHandler);
        return true;
    }

    /**
     * Gets the default wakeup period configured for this network
     *
     * @return the default wakeup, or null if not set
     */
    public Integer getDefaultWakeupPeriod() {
        return wakeupDefaultPeriod;
    }

    public UID getUID() {
        return thing.getUID();
    }

    public void removeFailedNode(int nodeId) {
        if (controller == null) {
            return;
        }
        controller.requestRemoveFailedNode(nodeId);
    }

    public void checkNodeFailed(int nodeId) {
        if (controller == null) {
            return;
        }
        controller.requestIsFailedNode(nodeId);
    }

    public void replaceFailedNode(int nodeId) {
        if (controller == null) {
            return;
        }
        controller.requestSetFailedNode(nodeId);
    }

    public void reinitialiseNode(int nodeId) {
        if (controller == null) {
            return;
        }
        controller.reinitialiseNode(nodeId);
    }

    public boolean healNode(int nodeId) {
        if (controller == null) {
            return false;
        }
        ZWaveNode node = controller.getNode(nodeId);
        if (node == null) {
            logger.debug("NODE {}: Can't be found!", nodeId);
            return false;
        }

        node.healNode();

        return true;
    }

    public boolean isControllerMaster() {
        return isMaster;
    }

    private void updateNeighbours() {
        if (controller == null) {
            return;
        }

        ZWaveNode node = getNode(getOwnNodeId());
        if (node == null) {
            return;
        }

        String neighbours = "";
        for (Integer neighbour : node.getNeighbors()) {
            if (neighbours.length() != 0) {
                neighbours += ',';
            }
            neighbours += neighbour;
        }
        getThing().setProperty(ZWaveBindingConstants.PROPERTY_NEIGHBOURS, neighbours);
        getThing().setProperty(ZWaveBindingConstants.PROPERTY_NODEID, Integer.toString(getOwnNodeId()));
    }

    /**
     * Gets the home ID associated with the controller.
     *
     * @return the home ID
     */
    public int getHomeId() {
        return controller.getHomeId();
    }
}
