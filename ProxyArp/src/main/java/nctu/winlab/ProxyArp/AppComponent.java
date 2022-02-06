package nctu.winlab.ProxyArp;

import java.nio.ByteBuffer;
import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableSet;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.ARP;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.Path;
import org.onosproject.net.PortNumber;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.host.HostService;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.edge.EdgePortService;
import java.util.*; 
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.config.ConfigFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Dictionary;
import java.util.Properties;
import java.util.Map;
import java.util.Optional;
import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{

    // Instantiates the relevant services.
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;
    
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;
    
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected EdgePortService edgeportservice;

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected Map<Ip4Address, MacAddress> arpTable = Maps.newConcurrentMap();

    private ApplicationId appId;
    private PacketProcessor processor;

    @Activate
    protected void activate() {
        log.info("Started");
        appId = coreService.getAppId("nctu.winlab.ProxyArp");

        processor = new SwitchPacketProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(2));

        packetService.requestPackets(DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_ARP).build(), PacketPriority.REACTIVE, appId, Optional.empty());
    }

    @Deactivate
    protected void deactivate() {
        log.info("Stopped");
        packetService.removeProcessor(processor);
    }

    private class SwitchPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext pc) {
            //log.info("process beginning");
            //log.info(pc.toString());
            //initMacTable(pc.inPacket().receivedFrom());

            forward(pc);

        }


        public void forward(PacketContext pc) {

            Short type = pc.inPacket().parsed().getEtherType();
            // only forward ipv4 and ARP packet
            if (type != Ethernet.TYPE_ARP) {
                return;
            }

            Ethernet eth = pc.inPacket().parsed();
            ARP arp = (ARP) eth.getPayload();

            Ip4Address senderip = Ip4Address.valueOf(arp.getSenderProtocolAddress());
            MacAddress sendermac = MacAddress.valueOf(arp.getSenderHardwareAddress());

            arpTable.put(senderip, sendermac);
            
            Ip4Address targetip = Ip4Address.valueOf(arp.getTargetProtocolAddress());
            MacAddress targetmac = arpTable.get(targetip);
            
            if(arp.getOpCode() == ARP.OP_REQUEST){

                //print arptable
                //Iterator<Map.Entry<Ip4Address, MacAddress>> iterator = map.entrySet().iterator();

                //if mapping doesn't exist
                if(targetmac == null){
                    log.info("TABLE MISS. Send request to edge ports");
                    //flood request packet to edge ports
                    ConnectPoint srccp = pc.inPacket().receivedFrom();
                    if(eth != null){
                        for(ConnectPoint cp : edgeportservice.getEdgePoints()){
                            TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
                            if(cp.equals(srccp)){

                            }
                            else{
                                builder.setOutput(cp.port());
                                packetService.emit(new DefaultOutboundPacket(cp.deviceId(),
                                    builder.build(),ByteBuffer.wrap(eth.serialize())));
                            }
                        }
                    }
                }
                else{
                    //send a arp reply packet immediately back to sender

                    String msg = "TABLE HIT. Requested MAC = " + targetmac.toString();
                    log.info(msg);
                    //packet out
                    Ethernet replyEthPkt = ARP.buildArpReply(targetip,targetmac,eth);
                    TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
                    ConnectPoint srccp = pc.inPacket().receivedFrom();

                    builder.setOutput(srccp.port());
                    packetService.emit(new DefaultOutboundPacket(srccp.deviceId(),
                        builder.build(),ByteBuffer.wrap(replyEthPkt.serialize())));

                }
            }
            else if(arp.getOpCode() == ARP.OP_REPLY){
                //learn address mapping of the target (already did before identifying request or reply)
                //forward packet to target
                String msg = "RECV REPLY. Request MAC = " + targetmac.toString();
                log.info(msg);
                Host dst = null;
                Set<Host> dst_others = hostService.getHostsByMac(targetmac);
                if(!dst_others.isEmpty()){
                    dst = dst_others.iterator().next();
                    //log.info(dst.toString());
                }
                else{
                    //log.info("can't find the destination");
                }

                TrafficTreatment.Builder builder = DefaultTrafficTreatment.builder();
                builder.setOutput(dst.location().port());
                packetService.emit(new DefaultOutboundPacket(dst.location().deviceId(),
                    builder.build(),ByteBuffer.wrap(eth.serialize())));

            }
            else{
                log.info("at the helpless area");
            }
            
        }


    }

}
