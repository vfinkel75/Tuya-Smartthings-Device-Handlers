/**
 *  Copyright 2021 Vadim Finkel
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

metadata {
    definition (name: "MOES Tuya ZigBee Dimmer", namespace: "moessoft", author: "VadimFinkel") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "Switch"
        capability "Switch Level"
        capability "Health Check"

        fingerprint profileId: "0104", deviceId: "0051", inClusters: "0000,0004,0005,EF00", outClusters: "0019,000A", manufacturer: "_TZE200_9i9dt8is", model: "TS0601", deviceJoinName: "MOES Zigbee Dimmer"
        fingerprint profileId: "0104", inClusters: "0000, 0003, 0004, 0005, 0006, 0008"
        
    }

    tiles(scale: 2) {
        multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true){
            tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
                attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#79b821", nextState:"turningOff"
                attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
                attributeState "turningOn", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#79b821", nextState:"turningOff"
                attributeState "turningOff", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
            }
            tileAttribute ("device.level", key: "SLIDER_CONTROL") {
                attributeState "level", action:"switch level.setLevel"
            }
        }
        standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        main "switch"
        details(["switch", "refresh"])
    }
}

private getCLUSTER_TUYA() { 0xEF00 }
private getSETDATA() { 0x00 }
private getINIT_DEVICE() { 0x03 }

// tuya DP type
private getDP_TYPE_BOOL() { "01" }
private getDP_TYPE_VALUE() { "02" }
private getDP_TYPE_ENUM() { "04" }

private sendTuyaCommand(dp, dp_type, fncmd) {
	log.debug "fncmd: ${fncmd}"
    zigbee.command(CLUSTER_TUYA, SETDATA, PACKET_ID + dp + dp_type + zigbee.convertToHexString(fncmd.length()/2, 4) + fncmd )
}
private getPACKET_ID() {
    state.packetID = ((state.packetID ?: 0) + 1 ) % 65536
    zigbee.convertToHexString(state.packetID, 4)
}

// Parse incoming device messages to generate events
def parse(String description) {
    def event = zigbee.getEvent(description)
    if (event) {
        sendEvent(event)
    } else if (description?.startsWith('read attr - raw:')) {
        map = parseReadAttr(description)
    } else if (description?.startsWith('catchall:')) {
    	Map descMap = zigbee.parseDescriptionAsMap(description)		
        def catchall = zigbee.parse(description)
        if (catchall.clusterId == 0xEF00) {
        	if ( descMap?.command == "01" || descMap?.command == "02" ) {
				def dp = zigbee.convertHexToInt(descMap?.data[2])
				def fncmd = zigbee.convertHexToInt(descMap?.data[6..-1].join(''))
				log.debug "dp=${dp} fncmd=${fncmd}"
                if (dp == 1) { // ON OFF
                	if (fncmd == 1) {sendEvent( name: 'switch', value: 'on')}
                	else if (fncmd == 0) {sendEvent( name: 'switch', value: 'off')}
                }
                else if (dp == 2) { //LEVEL
                	def Level = fncmd/10
                    sendEvent( name: 'level', value: Level, unit: '%' )
                }
              
            } 
            else log.debug "Unknown command: ${descMap?.command}"
            
		}
//        else {log.debug "not Tuya descMAP: ${descMap}"} // Not Tuya cluster
		else if (catchall.clusterId == 0x0000) {
        	log.debug "descMAP 00claster: ${descMap}"
            sendEvent(name: "DeviceWatch-DeviceStatus", value: "online")
        }
    }
    else {
        log.warn "DID NOT PARSE MESSAGE for description : $description"
        log.debug zigbee.parseDescriptionAsMap(description)
    }
    def results = map ? createEvent(map) : null
    return results
}


def off() {
    sendTuyaCommand("01", DP_TYPE_ENUM, zigbee.convertToHexString(0))
}

def on() {
    sendTuyaCommand("01", DP_TYPE_ENUM, zigbee.convertToHexString(1))
}

def setLevel(value) {
  	def cmds = sendTuyaCommand("02", DP_TYPE_VALUE, zigbee.convertToHexString(value*10, 8))
	cmds.each{ sendHubCommand(new physicalgraph.device.HubAction(it)) }
}

def refresh() {
    return zigbee.readAttribute(0x0006, 0x0000) +
            zigbee.readAttribute(0x0008, 0x0000) +
            zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 600, null) +
            zigbee.configureReporting(0x0008, 0x0000, 0x20, 1, 3600, 0x01)
}

def configure() {
    log.debug "Configuring Reporting and Bindings."

    return zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 600, null) +
            zigbee.configureReporting(0x0008, 0x0000, 0x20, 1, 3600, 0x01) +
            zigbee.readAttribute(0x0006, 0x0000) +
            zigbee.readAttribute(0x0008, 0x0000)
}


// Check catchall for battery voltage data to pass to getBatteryResult for conversion to percentage report
private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def catchall = zigbee.parse(description)
	log.debug catchall

	if (catchall.clusterId == 0x0000) {
		def MsgLength = catchall.data.size()
        log.debug "Level is: ${catchall.data.get(MsgLength-1)} ${catchall.data.get(MsgLength-2)}"
		// Xiaomi CatchAll does not have identifiers, first UINT16 is Battery
		if ((catchall.data.get(0) == 0x01 || catchall.data.get(0) == 0x02) && (catchall.data.get(1) == 0xFF)) {
			for (int i = 4; i < (MsgLength-3); i++) {
				if (catchall.data.get(i) == 0x21) { // check the data ID and data type
					// next two bytes are the battery voltage
					resultMap = getBatteryResult((catchall.data.get(i+2)<<8) + catchall.data.get(i+1))
					break
				}
			}
		}
	}
	return resultMap
}