package nctu.winlab.bridge;

import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;

import java.util.Map;
import java.util.Optional;

import java.util.Dictionary;
import java.util.Properties;

import static org.onlab.util.Tools.get;

@Component(immediate = true)
public class AppComponent {

    // Instantiates the relevant services.
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;
    
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected Map<DeviceId, Map<MacAddress, PortNumber>> macTables = Maps.newConcurrentMap();
    private ApplicationId appId;
    private PacketProcessor processor;

    @Activate
    protected void activate() {
        log.info("Started");
        appId = coreService.getAppId("nctu.winlab.bridge");

        processor = new SwitchPacketProcessor();
        packetService.addProcessor(processor, PacketProcessor.director(3));

        packetService.requestPackets(DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4).build(), PacketPriority.REACTIVE, appId, Optional.empty());
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
            log.info(pc.toString());
            initMacTable(pc.inPacket().receivedFrom());

            forward(pc);

        }

        public void flood(PacketContext pc) {
            pc.treatmentBuilder().setOutput(PortNumber.FLOOD);
            pc.send();
        }

        public void forward(PacketContext pc) {

            Short type = pc.inPacket().parsed().getEtherType();
            // only forward ipv4 and ARP packet
            if (type != Ethernet.TYPE_IPV4 && type != Ethernet.TYPE_ARP) {
                return;
            }

            //parsing
            ConnectPoint cp = pc.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = macTables.get(cp.deviceId());
            MacAddress srcMac = pc.inPacket().parsed().getSourceMAC();
            MacAddress dstMac = pc.inPacket().parsed().getDestinationMAC();
            macTable.put(srcMac, cp.port());
            PortNumber outPort = macTable.get(dstMac);

            log.info("Add MAC address ==> switch: " + cp.deviceId() + ", MAC: " + srcMac + ", port: " + cp.port());

            if (outPort != null) { //if find the corresponding outport on MAC table, add flow rule and send
                log.info("MAC " + srcMac + " is matched on " + cp.deviceId() + "! Install flow rule!");
                pc.treatmentBuilder().setOutput(outPort);

                //write the flow rule
                FlowRule fr = DefaultFlowRule.builder()
                        .withSelector(DefaultTrafficSelector.builder().matchEthDst(dstMac).matchEthSrc(srcMac).build())
                        .withTreatment(DefaultTrafficTreatment.builder().setOutput(outPort).build())
                        .forDevice(cp.deviceId())
                        .withPriority(30)
                        .makeTemporary(30)
                        .fromApp(appId).build();

                //add flow rule
                flowRuleService.applyFlowRules(fr);
                pc.send();
            } else { //if can't find the corresponding output port, then flood
                log.info("MAC " + srcMac + " is missed on " + cp.deviceId() + "! Flood packet!");
                flood(pc);
            }
        }

        private void initMacTable(ConnectPoint cp) {
            macTables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());

        }
    }
}
