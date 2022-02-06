/*
 * Copyright 2022-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.vlanbasedsr;

import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_ADDED;
import static org.onosproject.net.config.NetworkConfigEvent.Type.CONFIG_UPDATED;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.VlanId;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ElementId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.PortNumber;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.PathService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.onosproject.cfg.ComponentConfigService;

import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.lang.Integer;
import java.util.*;
import java.util.Dictionary;
import java.util.Properties;
import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent{
    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;
    private PacketProcessor proc;
    private String dhcpServer;

    
    
    private final NameConfigListener cfgListener = new NameConfigListener();

    private final ConfigFactory factory =
        new ConfigFactory<ApplicationId, NameConfig>(APP_SUBJECT_FACTORY, NameConfig.class, "vlanbasedsr"){
            @Override
            public NameConfig createConfig(){
                return new NameConfig();
            }
        };
    
    
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;
    
    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PathService pathService;

    protected Map<MacAddress, DeviceId> macdeviceidTable = Maps.newConcurrentMap();
    protected Map<MacAddress, PortNumber> macportTable = Maps.newConcurrentMap();
    protected Map<DeviceId, IpPrefix> subnetTable = Maps.newConcurrentMap();
    protected Map<DeviceId, VlanId> vlanTable = Maps.newConcurrentMap();

    @Activate
    protected void activate(){
        appId = coreService.registerApplication("nctu.winlab.vlanbasedsr");
        proc = new MyPacketProcessor();

        packetService.addProcessor(proc, PacketProcessor.director(3));
        packetService.requestPackets(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).build(), PacketPriority.REACTIVE, appId);

        cfgService.addListener(cfgListener);
        cfgService.registerConfigFactory(factory);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate(){
        packetService.removeProcessor(proc);
        cfgService.removeListener(cfgListener);
        cfgService.unregisterConfigFactory(factory);
        log.info("Stopped");
    }

    
    private class NameConfigListener implements NetworkConfigListener{
        @Override
        public void event(NetworkConfigEvent event){
            if ((event.type() == CONFIG_ADDED || event.type() == CONFIG_UPDATED)
                && event.configClass().equals(NameConfig.class)){
                NameConfig config = cfgService.getConfig(appId, NameConfig.class);
                if (config != null){
                    dhcpServer = config.serverLocation();
                    log.info("DHCP server is at {}", dhcpServer); 
                }
            }
        }
    }
    

    private class MyPacketProcessor implements PacketProcessor{
        @Override
        public void process(PacketContext pc){

            Short type = pc.inPacket().parsed().getEtherType();
            if (type != Ethernet.TYPE_IPV4){
                return;
            }

            processPacketOut(pc);
        }

        public void processPacketOut(PacketContext pc){
            Short type = pc.inPacket().parsed().getEtherType();
            // only forward ipv4 and ARP packet
            if (type != Ethernet.TYPE_IPV4) {
                return;
            }

            /*
            //parse
            ConnectPoint cp = pc.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = macTables.get(cp.deviceId());
            MacAddress srcMac = pc.inPacket().parsed().getSourceMAC();
            macTable.put(srcMac, cp.port());

            //init
            if(cp.deviceId().equals(DeviceId.deviceId("of:0000000000000001"))){
                Integer obj = new Integer(101);
                short s = obj.shortValue();
                vlanTable.put(cp.deviceId(), VlanId.vlanId(s));
            }
            else if(cp.deviceId().equals(DeviceId.deviceId("of:0000000000000002"))){
                Integer obj = new Integer(102);
                short s = obj.shortValue();
                vlanTable.put(cp.deviceId(), VlanId.vlanId(s));
                subnetTable.put(cp.deviceId(), IpPrefix.valueOf("10.0.2.0/24"));
            }
            else if(cp.deviceId().equals(DeviceId.deviceId("of:0000000000000003"))){
                Integer obj = new Integer(103);
                short s = obj.shortValue();
                vlanTable.put(cp.deviceId(), VlanId.vlanId(s));
                subnetTable.put(cp.deviceId(), IpPrefix.valueOf("10.0.3.0/24"));
            }


            //test
            for(DeviceId keyid: subnetTable.keySet()){
                log.info("DeviceId : " + keyid + ", IpPrefix : " + subnetTable.get(keyid));
            }
            for(DeviceId keyid: vlanTable.keySet()){
                log.info("DeviceId : " + keyid + ", VlanId : " + vlanTable.get(keyid));
            }
            */

            macdeviceidTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:02"), DeviceId.deviceId("of:0000000000000002"));
            macdeviceidTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:03"), DeviceId.deviceId("of:0000000000000002"));
            macdeviceidTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:04"), DeviceId.deviceId("of:0000000000000003"));
            macdeviceidTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:05"), DeviceId.deviceId("of:0000000000000003"));

            macportTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:02"), PortNumber.portNumber("3"));
            macportTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:03"), PortNumber.portNumber("4"));
            macportTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:04"), PortNumber.portNumber("2"));
            macportTable.put(MacAddress.valueOf("ea:e9:78:fb:fd:05"), PortNumber.portNumber("3"));

            subnetTable.put(DeviceId.deviceId("of:0000000000000002"), IpPrefix.valueOf("10.0.2.0/24"));
            subnetTable.put(DeviceId.deviceId("of:0000000000000003"), IpPrefix.valueOf("10.0.3.0/24"));

            Integer obj1 = new Integer(101);
            short s1 = obj1.shortValue();
            Integer obj2 = new Integer(102);
            short s2 = obj2.shortValue();
            Integer obj3 = new Integer(103);
            short s3 = obj3.shortValue();
            vlanTable.put(DeviceId.deviceId("of:0000000000000001"), VlanId.vlanId(s2));
            vlanTable.put(DeviceId.deviceId("of:0000000000000002"), VlanId.vlanId(s1));
            vlanTable.put(DeviceId.deviceId("of:0000000000000003"), VlanId.vlanId(s3));

            FlowRule fr1to3 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf("10.0.3.0/24")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().pushVlan().setVlanId(VlanId.vlanId(s3)).setOutput(PortNumber.portNumber("1")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000002"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr1to3);

            FlowRule fr2to3 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchVlanId(VlanId.vlanId(s3)).build())
                            .withTreatment(DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber("2")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000001"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr2to3);

            FlowRule fr3toh4 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchVlanId(VlanId.vlanId(s3)).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:04")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().popVlan().setOutput(PortNumber.portNumber("2")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000003"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr3toh4);

            FlowRule fr3toh5 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchVlanId(VlanId.vlanId(s3)).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:05")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().popVlan().setOutput(PortNumber.portNumber("3")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000003"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr3toh5);

            FlowRule fr3to1 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchIPDst(IpPrefix.valueOf("10.0.2.0/24")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().pushVlan().setVlanId(VlanId.vlanId(s1)).setOutput(PortNumber.portNumber("1")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000003"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr3to1);

            FlowRule fr2to1 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchVlanId(VlanId.vlanId(s1)).build())
                            .withTreatment(DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber("1")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000001"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr2to1);

            FlowRule fr1toh2 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchVlanId(VlanId.vlanId(s1)).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:02")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().popVlan().setOutput(PortNumber.portNumber("3")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000002"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr1toh2);

            FlowRule fr1toh3 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchVlanId(VlanId.vlanId(s1)).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:03")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().popVlan().setOutput(PortNumber.portNumber("4")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000002"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(fr1toh3);

            FlowRule frh2toh3 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:03")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber("4")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000002"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(frh2toh3);

            FlowRule frh3toh2 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:02")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber("3")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000002"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(frh2toh3);

            FlowRule frh4toh5 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:05")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber("3")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000003"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(frh4toh5);

            FlowRule frh5toh4 = DefaultFlowRule.builder()
                            .withSelector(DefaultTrafficSelector.builder().matchEthType(Ethernet.TYPE_IPV4).matchEthDst(MacAddress.valueOf("ea:e9:78:fb:fd:04")).build())
                            .withTreatment(DefaultTrafficTreatment.builder().setOutput(PortNumber.portNumber("2")).build())
                            .forDevice(DeviceId.deviceId("of:0000000000000003"))
                            .withPriority(10)
                            .makeTemporary(30)
                            .fromApp(appId).build(); 

            flowRuleService.applyFlowRules(frh5toh4);
        }

    }
}
