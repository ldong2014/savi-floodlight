package net.floodlightcontroller.savi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.IPv6;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingDecision.RoutingAction;
import net.floodlightcontroller.routing.RoutingDecision;
import net.floodlightcontroller.savi.action.Action;
import net.floodlightcontroller.savi.action.Action.ActionFactory;
import net.floodlightcontroller.savi.action.BindIPv4Action;
import net.floodlightcontroller.savi.action.BindIPv6Action;
import net.floodlightcontroller.savi.action.CheckIPv4BindingAction;
import net.floodlightcontroller.savi.action.CheckIPv6BindingAction;
import net.floodlightcontroller.savi.action.FloodAction;
import net.floodlightcontroller.savi.action.PacketOutAction;
import net.floodlightcontroller.savi.action.UnbindIPv4Action;
import net.floodlightcontroller.savi.action.UnbindIPv6Action;
import net.floodlightcontroller.savi.binding.Binding;
import net.floodlightcontroller.savi.binding.BindingManager;
import net.floodlightcontroller.savi.rest.SAVIRestRoute;
import net.floodlightcontroller.savi.service.SAVIProviderService;
import net.floodlightcontroller.savi.service.SAVIService;
import net.floodlightcontroller.storage.IStorageSourceService;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import net.floodlightcontroller.topology.ITopologyService;

/**
 * Provider
 * @author zhouyu
 *
 */
public class Provider implements IFloodlightModule,
IOFSwitchListener, IOFMessageListener, SAVIProviderService, ILinkDiscoveryListener{
	
	/**
	 * Priority
	 */
	static final int PROTOCOL_LAYER_PRIORITY = 1;
	static final int SERVICE_LAYER_PRIORITY = 2;
	static final int BINDING_LAYER_PRIORITY = 3;
	static final int EXTENSION_LAYER_PRIORITY = 4;
	
	static final Logger log = LoggerFactory.getLogger(SAVIProviderService.class);
	
	/**
	 * Floodlight service
	 */
	protected IFloodlightProviderService floodlightProvider;
	protected IOFSwitchService switchService;
	protected IDeviceService deviceService;
	protected ITopologyService topologyService;
	protected IRestApiService restApiService;
	protected IThreadPoolService threadPoolService;
	protected ILinkDiscoveryService linkDiscoveryService;
	
	protected SingletonTask updateTask;
	/**
	 * Service
	 */
	private List<SAVIService> saviServices;
	private BindingManager manager;
	
	/**
	 * rules 
	 */
	private List<Match> serviceRules;
	private List<Match> protocolRules;
	private Set<SwitchPort> securityPort;
	protected Queue<LDUpdate> updateQueue;
	
	public static final int SAVI_PROVIDER_APP_ID = 1000;
	public static final TableId FLOW_TABLE_ID = TableId.of(1);
	
	public static int securityTableCounter = 0;
	
	/**
	 * Static cookie 
	 */
	static {
		AppCookie.registerApp(SAVI_PROVIDER_APP_ID, "Forwarding");
	}
	public static final U64 cookie = AppCookie.makeCookie(SAVI_PROVIDER_APP_ID, 0);
	
	/**
	 * Process packet in message.
	 * @param sw
	 * @param pi
	 * @param cntx
	 */
	private Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort()
				: pi.getMatch().get(MatchField.IN_PORT));
		
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IRoutingDecision decision = IRoutingDecision.rtStore.get(cntx, IRoutingDecision.CONTEXT_DECISION);
		
		SwitchPort switchPort = new SwitchPort(sw.getId(), inPort);
		RoutingAction routingAction = null;
		
		if (decision == null) {
			decision = new RoutingDecision(sw.getId(), inPort,
					IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE), RoutingAction.FORWARD);
		}
		
		if(topologyService.isEdge(sw.getId(), inPort)) {
		// SAVI service process
			for(SAVIService s : saviServices) {
				if (s.match(eth)) {
					routingAction = s.process(switchPort, eth);
					break;
				}
			}
		}
		
		// Process
		if(routingAction == null){
			 routingAction = process(switchPort, eth);
		}
		
		if(routingAction != null){
			decision.setRoutingAction(routingAction);
		}

		decision.addToContext(cntx);
		if(routingAction == RoutingAction.NONE){
			return Command.STOP;
		}
		else {
			return Command.CONTINUE;
		}
	}
	
	/**
	 * Add savi servie to provider
	 * @param service
	 */
	@Override
	public void addSAVIService(SAVIService service) {
		// TODO Auto-generated method stub
		saviServices.add(service);
		serviceRules.addAll(service.getMatches());
	}
	
	/**
	 * Process actions from savi service.
	 * @param actions
	 */
	@Override
	public boolean pushActions(List<Action> actions) {
		// TODO Auto-generated method stub
		for(Action action:actions){
			switch(action.getType()){
			case FLOOD:
				doFlood((FloodAction)action);
				break;
			case PACKET_OUT:
			case PACKET_OUT_MULTI_PORT:
				doPacketOut((PacketOutAction)action);
				break;
			case BIND_IPv4:
				doBindIPv4((BindIPv4Action)action);
				break;
			case BIND_IPv6:
				doBindIPv6((BindIPv6Action)action);
				break;
			case UNBIND_IPv4:
				doUnbindIPv4((UnbindIPv4Action)action);
				break;
			case UNBIND_IPv6:
				doUnbindIPv6((UnbindIPv6Action)action);
				break;
			case CHECK_IPv4_BINDING:
				return doCheckIPv4BInding((CheckIPv4BindingAction)action);
			case CHECK_IPv6_BINDING:
				return doCheckIPv6Binding((CheckIPv6BindingAction)action);
			default:
				break;
			}
		}
		return true;
	}
	
	/**
	 * 
	 */
	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return "savi";
	}

	/**
	 * Floodlight override function
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;//(type.equals(OFType.PACKET_IN) && (name.equals("topology") || name.equals("devicemanager")));
	}

	/**
	 * Floodlight override function
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;//type.equals(OFType.PACKET_IN) || name.equals("forwarding");
	}

	/**
	 * Dispatch openflow message
	 * @param sw
	 * @param msg
	 * @param cntx
	 */
	@Override
	public Command receive(IOFSwitch sw, OFMessage msg,
			FloodlightContext cntx) {
		// TODO Auto-generated method stub
		
		switch (msg.getType()) {
		case PACKET_IN:
			return processPacketInMessage(sw, (OFPacketIn) msg, cntx);
		case ERROR:
			log.info("ERROR");
		default:
			break;
		}
		
		return Command.CONTINUE;
	}

	/**
	 * Module service
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		Collection<Class<? extends IFloodlightService>> services = new ArrayList<Class<? extends IFloodlightService>>();
		services.add(SAVIProviderService.class);
		return services;
	}

	/**
	 * Service implementation.
	 */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		Map<Class<? extends IFloodlightService>, IFloodlightService> serviceImpls = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		serviceImpls.put(SAVIProviderService.class, this);
		return serviceImpls;
	}

	/**
	 * Module dependencies.
	 */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
		Collection<Class<? extends IFloodlightService>> dependencies = new ArrayList<Class<? extends IFloodlightService>>();
		dependencies.add(IFloodlightProviderService.class);
		dependencies.add(IOFSwitchService.class);
		dependencies.add(IDeviceService.class);
		dependencies.add(ITopologyService.class);
		dependencies.add(IStorageSourceService.class);
		dependencies.add(IRestApiService.class);
		dependencies.add(ILinkDiscoveryService.class);
		dependencies.add(IThreadPoolService.class);
		
		return dependencies;
	}

	/**
	 * Initialize module.
	 */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		floodlightProvider 	 = context.getServiceImpl(IFloodlightProviderService.class);
		switchService 	   	 = context.getServiceImpl(IOFSwitchService.class);
		deviceService 	   	 = context.getServiceImpl(IDeviceService.class);
		topologyService 	 = context.getServiceImpl(ITopologyService.class);
		restApiService 		 = context.getServiceImpl(IRestApiService.class);
		threadPoolService	 = context.getServiceImpl(IThreadPoolService.class);
		linkDiscoveryService = context.getServiceImpl(ILinkDiscoveryService.class);
		
		saviServices 		= new ArrayList<>();
		manager 			= new BindingManager();
		
		updateQueue = new ConcurrentLinkedQueue<>();
		
		serviceRules		= new ArrayList<>();
		protocolRules		= new ArrayList<>();
		
		{
			Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
			mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
			protocolRules.add(mb.build());
		
			mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
			mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
			protocolRules.add(mb.build());
		}
		
		securityPort = new HashSet<>();
		
		{
			// Pre-set security port.
			SwitchPort switchPort = new SwitchPort(DatapathId.of(1L),OFPort.of(1));
			securityPort.add(switchPort);
		}
	} 

	/**
	 * Start up module
	 */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProvider.addOFMessageListener(OFType.ERROR, this);
		switchService.addOFSwitchListener(this);
		restApiService.addRestletRoutable(new SAVIRestRoute());
		linkDiscoveryService.addListener(this);
		
		ScheduledExecutorService ses = threadPoolService.getScheduledExecutor();
		
		updateTask = new SingletonTask(ses, new Runnable() {
			
			@Override
			public void run() {
				// TODO Auto-generated method stub
				while(updateQueue.peek() != null){
					LDUpdate update = updateQueue.remove();
					
					switch(update.getOperation()){
					case PORT_UP:
						if(!topologyService.isEdge(update.getSrc(), update.getSrcPort())){
							log.info("LOG");
							List<OFInstruction> instructions = new ArrayList<>();
							instructions.add(OFFactories.getFactory(OFVersion.OF_13).instructions().gotoTable(FLOW_TABLE_ID));
							
							Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
							mb.setExact(MatchField.IN_PORT, update.getSrcPort());
							
							doFlowMod(update.getSrc(), TableId.of(0), mb.build(), null, instructions, BINDING_LAYER_PRIORITY);
						}
						break;
					default:
					}
					
				}
				updateTask.reschedule(1, TimeUnit.SECONDS);
			}
		});
		updateTask.reschedule(1, TimeUnit.SECONDS);
	}

	/**
	 * Listen switch add message.
	 */
	@Override
	public void switchAdded(DatapathId switchId) {
		// TODO Auto-generated method stub
		manager.addSwitch(switchId);
		
		for(Match match:protocolRules){
			List<OFAction> actions = new ArrayList<>();
			doFlowMod(switchId, TableId.of(0), match, actions, null, PROTOCOL_LAYER_PRIORITY);
		}
		
		for(Match match:serviceRules){
			List<OFAction> actions = new ArrayList<>();
			actions.add(OFFactories.getFactory(OFVersion.OF_13).actions().output(OFPort.CONTROLLER, Integer.MAX_VALUE));
			doFlowMod(switchId, TableId.of(0), match, actions, null, SERVICE_LAYER_PRIORITY);
		}
		
		List<OFInstruction> instructions = new ArrayList<>();
		instructions.add(OFFactories.getFactory(OFVersion.OF_13).instructions().gotoTable(FLOW_TABLE_ID));
		Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
		doFlowMod(switchId, TableId.of(0), mb.build(), null, instructions, 0);
		
		List<OFAction> actions = new ArrayList<>();
		actions.add(OFFactories.getFactory(OFVersion.OF_13).actions().output(OFPort.CONTROLLER, Integer.MAX_VALUE));
		doFlowMod(switchId, FLOW_TABLE_ID, mb.build(), actions, null, 0);
		
		for(SwitchPort switchPort:securityPort){
			if(switchPort.getSwitchDPID().equals(switchId)){
				mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
				mb.setExact(MatchField.IN_PORT, switchPort.getPort());
					
				instructions = new ArrayList<>();
				instructions.add(OFFactories.getFactory(OFVersion.OF_13).instructions().gotoTable(FLOW_TABLE_ID));
				doFlowMod(switchPort.getSwitchDPID(), TableId.of(0), mb.build(), null, instructions, BINDING_LAYER_PRIORITY);
			}
		}
	}

	/**
	 * Listen switch remove message.
	 */
	@Override
	public void switchRemoved(DatapathId switchId) {
		// TODO Auto-generated method stub
		manager.removeSwitch(switchId);
		List<Action> actions = new ArrayList<>();
		actions.add(ActionFactory.getClearSwitchBindingAction(switchId));
		for(SAVIService s:saviServices){
			s.pushActins(actions);
		}
	}

	@Override
	public void switchActivated(DatapathId switchId) {
		
	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void switchChanged(DatapathId switchId) {
		// TODO Auto-generated method stub
		
	}
	
	/**
	 * Process the frame
	 * @param switchPort
	 * @param eth
	 * @return routing acttion
	 */
	protected RoutingAction process(SwitchPort switchPort, Ethernet eth){
		MacAddress macAddress = eth.getSourceMACAddress();
		
		if(securityPort.contains(switchPort)){
			if(macAddress.isBroadcast()){
				doFlood(switchPort, eth.serialize());
				return RoutingAction.NONE;
			}
			else{
				return RoutingAction.FORWARD_OR_FLOOD;
			}
		}
		
		if(eth.getEtherType() == EthType.IPv4){
			IPv4 ipv4 = (IPv4)eth.getPayload();
			IPv4Address address = ipv4.getSourceAddress();
			if(manager.check(switchPort, macAddress, address)){
				//doFlood(switchPort, eth.serialize());
				return RoutingAction.FORWARD_OR_FLOOD;
			}
			else if(address.isUnspecified()){
				return RoutingAction.FORWARD_OR_FLOOD;
			}
			else {
				return RoutingAction.NONE;
			}
		}
		if(eth.getEtherType() == EthType.IPv6){
			IPv6 ipv6 = (IPv6)eth.getPayload();
			IPv6Address address = ipv6.getSourceAddress();
			if(address.isUnspecified()){
				return RoutingAction.FORWARD_OR_FLOOD;
			}
			else if(manager.check(switchPort, macAddress, address)){
				if(ipv6.getDestinationAddress().isBroadcast()||ipv6.getDestinationAddress().isMulticast()){
					return RoutingAction.MULTICAST;
				}
				else{
					return RoutingAction.FORWARD_OR_FLOOD;
				}
				
			}
			else{
				return RoutingAction.NONE;
			}
		}
		else if(eth.getEtherType() == EthType.ARP){
			ARP arp = (ARP)eth.getPayload();
			IPv4Address address = arp.getSenderProtocolAddress();
			if(manager.check(switchPort, macAddress, address)){
				return RoutingAction.FORWARD_OR_FLOOD;
			}
			else if(address.isUnspecified()){
				return RoutingAction.FORWARD_OR_FLOOD;
			}
			else {
				return RoutingAction.NONE;
			}
		}
		return null;
	}
	
	/**
	 * Do flood
	 * @param action
	 */
	protected void doFlood(FloodAction action){
		SwitchPort inSwitchPort = new SwitchPort(action.getSwitchId(), action.getInPort());
		byte[] data = action.getEthernet().serialize();
		doFlood(inSwitchPort, data);
	}
	/**
	 * Do flood.
	 * @param inSwitchPort
	 * @param data
	 */
	protected void doFlood(SwitchPort inSwitchPort, byte[] data){
		Collection<? extends IDevice> tmp = deviceService.getAllDevices();
		for (IDevice d : tmp) {
			SwitchPort[] switchPorts = d.getAttachmentPoints();
			for (SwitchPort switchPort : switchPorts) {
				if (!switchPort.equals(inSwitchPort)) {
					doPacketOut(switchPort, data);
				}
			}
		}
	}
	
	/**
	 * Do packet out.
	 * @param action
	 */
	protected void doPacketOut(PacketOutAction action) {
		
		doPacketOut(action.getSwitchId(),
					action.getInPort(),
					action.getOutPorts(),
					action.getEthernet().serialize());
	
	}
	
	/**
	 * Do packet out
	 * @param switchPort
	 * @param data
	 */
	protected void doPacketOut(SwitchPort switchPort, byte[] data) {
		
		IOFSwitch sw = switchService.getActiveSwitch(switchPort.getSwitchDPID());
		OFPort port = switchPort.getPort();
		
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().output(port, Integer.MAX_VALUE));
		
		pob.setActions(actions)
		   .setBufferId(OFBufferId.NO_BUFFER)
		   .setData(data)
		   .setInPort(OFPort.CONTROLLER);
		
		sw.write(pob.build());
	
	}
	
	/**
	 * Do packet out
	 * @param switchId
	 * @param inPort
	 * @param outPorts
	 * @param data
	 */
	protected void doPacketOut(DatapathId switchId, OFPort inPort, List<OFPort> outPorts, byte[] data) {
		
		IOFSwitch sw = switchService.getActiveSwitch(switchId);
		
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		
		List<OFAction> actions = new ArrayList<OFAction>();
		for(OFPort port:outPorts) {
			actions.add(sw.getOFFactory().actions().output(port, Integer.MAX_VALUE));
		}
		
		pob.setActions(actions)
		   .setBufferId(OFBufferId.NO_BUFFER)
		   .setData(data)
		   .setInPort(inPort);
		
		sw.write(pob.build());
	}
	
	/**
	 * Do bind ipv4.
	 * @param action
	 */
	protected void doBindIPv4(BindIPv4Action action){
		Binding<?> binding = action.getBinding();
		log.info("BIND "+binding.getAddress());
		
		manager.addBinding(binding);
		
		if(securityPort.contains(binding.getSwitchPort())){
			return;
		}
		
		Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
		mb.setExact(MatchField.ETH_SRC, binding.getMacAddress());
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
		mb.setExact(MatchField.IPV4_SRC, (IPv4Address)binding.getAddress());
		mb.setExact(MatchField.IN_PORT, binding.getSwitchPort().getPort());
		
		List<OFInstruction> instructions = new ArrayList<>();
		instructions.add(OFFactories.getFactory(OFVersion.OF_13).instructions().gotoTable(FLOW_TABLE_ID));
		doFlowMod(binding.getSwitchPort().getSwitchDPID(), TableId.of(0), mb.build(), null, instructions, BINDING_LAYER_PRIORITY);	
	}
	
	/**
	 * Do bind ipv6
	 * @param action
	 */
	protected void doBindIPv6(BindIPv6Action action){
		Binding<?> binding = action.getBinding();
		log.info("BIND "+binding.getAddress().toString()+"  "+binding.getSwitchPort().getSwitchDPID());
		
		manager.addBinding(binding);
		
		if(securityPort.contains(binding.getSwitchPort())){
			return;
		}
		Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
		mb.setExact(MatchField.ETH_SRC, binding.getMacAddress());
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
		mb.setExact(MatchField.IPV6_SRC, (IPv6Address)binding.getAddress());
		mb.setExact(MatchField.IN_PORT, binding.getSwitchPort().getPort());
		
		List<OFInstruction> instructions = new ArrayList<>();
		instructions.add(OFFactories.getFactory(OFVersion.OF_13).instructions().gotoTable(FLOW_TABLE_ID));
		doFlowMod(binding.getSwitchPort().getSwitchDPID(), TableId.of(0), mb.build(), null, instructions, BINDING_LAYER_PRIORITY);
	
	}
	
	/**
	 * Do unbind ipv4.
	 * @param action
	 */
	protected void doUnbindIPv4(UnbindIPv4Action action) {
		manager.delBinding(action.getIpv4Address());
		Binding<?> binding = action.getBinding();
		if(securityPort.contains(binding.getSwitchPort())){
			return;
		}
		Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
		mb.setExact(MatchField.ETH_SRC, binding.getMacAddress());
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
		mb.setExact(MatchField.IPV4_SRC, (IPv4Address)binding.getAddress());
		mb.setExact(MatchField.IN_PORT, binding.getSwitchPort().getPort());
		
		doFlowRemove(binding.getSwitchPort().getSwitchDPID(), TableId.of(0), mb.build());
	}
	
	/**
	 * Do unbind ipv6.
	 * @param action
	 */
	protected void doUnbindIPv6(UnbindIPv6Action action) {
		manager.delBinding(action.getIPv6Address());
		
		Binding<?> binding = action.getBinding();
		if(securityPort.contains(binding.getSwitchPort())){
			return;
		}
		Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
		mb.setExact(MatchField.ETH_SRC, binding.getMacAddress());
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
		mb.setExact(MatchField.IPV6_SRC, (IPv6Address)binding.getAddress());
		mb.setExact(MatchField.IN_PORT, binding.getSwitchPort().getPort());
		
		doFlowRemove(binding.getSwitchPort().getSwitchDPID(), TableId.of(0), mb.build());
	
	}
	
	/**
	 * Do check
	 * @param action
	 * @return
	 */
	protected boolean doCheckIPv4BInding(CheckIPv4BindingAction action){
		return manager.check(action.getSwitchPort(), action.getMacAddress(), action.getIPv4Address());
	}
	
	/**
	 * Do check
	 * @param action
	 * @return
	 */
	protected boolean doCheckIPv6Binding(CheckIPv6BindingAction action) {
		return manager.check(action.getSwitchPort(), action.getMacAddress(), action.getIPv6Address());
	}
	
	/**
	 * Do flow modification.
	 * @param switchId
	 * @param tableId
	 * @param match
	 * @param actions
	 * @param instructions
	 * @param priority
	 */
	protected void doFlowMod(DatapathId switchId,TableId tableId,Match match, List<OFAction> actions, List<OFInstruction> instructions,int priority){
		OFFlowAdd.Builder fab = OFFactories.getFactory(OFVersion.OF_13).buildFlowAdd();
		
		fab.setCookie(cookie)
		   .setTableId(tableId)
		   .setHardTimeout(0)
		   .setIdleTimeout(0)
		   .setPriority(priority)
		   .setBufferId(OFBufferId.NO_BUFFER)
		   .setMatch(match);
		
		if(actions != null){
			fab.setActions(actions);
		}
		
		if(instructions != null){
			fab.setInstructions(instructions);
		}
		
		IOFSwitch sw = switchService.getSwitch(switchId);
		
		if(sw!= null){
			sw.write(fab.build());
		}
		
	}
	
	/**
	 * Do flow remove
	 * @param switchId
	 * @param tableId
	 * @param match
	 */
	protected void doFlowRemove(DatapathId switchId, TableId tableId, Match match) {
		OFFlowDelete.Builder fdb = OFFactories.getFactory(OFVersion.OF_13).buildFlowDelete();
		
		fdb.setMatch(match)
		   .setCookie(cookie)
		   .setTableId(tableId)
		   .setPriority(BINDING_LAYER_PRIORITY)
		   .setBufferId(OFBufferId.NO_BUFFER);
		
		IOFSwitch sw = switchService.getSwitch(switchId);
		
		if(sw!= null){
			sw.write(fdb.build());
		}
	}

	/**
	 * Add security port, called by rest api.
	 */
	@Override
	public boolean addSecurityPort(SwitchPort switchPort) {
		// TODO Auto-generated method stub
		IOFSwitch sw = switchService.getActiveSwitch(switchPort.getSwitchDPID());
		if(sw!=null){
			Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
			mb.setExact(MatchField.IN_PORT, switchPort.getPort());
			
			List<OFInstruction> instructions = new ArrayList<>();
			instructions.add(OFFactories.getFactory(OFVersion.OF_13).instructions().gotoTable(FLOW_TABLE_ID));
			doFlowMod(switchPort.getSwitchDPID(), TableId.of(0), mb.build(), null, instructions, BINDING_LAYER_PRIORITY);
		}
		return securityPort.add(switchPort);
	}

	/**
	 * Add delete security service, called rest api.
	 */
	@Override
	public boolean delSecurityPort(SwitchPort switchPort) {
		// TODO Auto-generated method stub
		Match.Builder mb = OFFactories.getFactory(OFVersion.OF_13).buildMatch();
		mb.setExact(MatchField.IN_PORT, switchPort.getPort());
		doFlowRemove(switchPort.getSwitchDPID(), TableId.of(0), mb.build());
		
		return securityPort.remove(switchPort);
	}

	/**
	 * Get security ports.
	 */
	@Override
	public Set<SwitchPort> getSecurityPorts() {
		// TODO Auto-generated method stub
		return securityPort;
	}

	/** 
	 * Get binding entries.
	 */
	@Override
	public List<Binding<?>> getBindings() {
		// TODO Auto-generated method stub
		return manager.getBindings();
	}

	@Override
	public void linkDiscoveryUpdate(LDUpdate update) {
		// TODO Auto-generated method stub
		updateQueue.add(update);
	}

	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		// TODO Auto-generated method stub
		updateQueue.addAll(updateList);
	}
	
}

