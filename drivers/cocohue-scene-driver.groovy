/*
 * =============================  CoCoHue Scene (Driver) ===============================
 *
 *  Copyright 2019-2020 Robert Morris
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
 * =======================================================================================
 *
 *  Last modified: 2019-01-23
 * 
 *  Changelog:
 * 
 *  v1.5b - Initial public release
 *  v1.7  - Added configure() per Capability requirement
 *  v1.9  - Added off() functionality
 *
 */ 

metadata {
    definition (name: "CoCoHue Scene", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-scene-driver.groovy") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Light"
        capability "PushableButton"
        
        command "push",	[[name:"NUMBER", type: "NUMBER", description: "Button number" ]]
    }
       
   preferences {
        input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
        input(name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
    }
}

def installed(){
    log.debug "Installed..."
    initialize()
}

def updated(){
    log.debug "Updated..."
    initialize()
}

def initialize() {
    log.debug "Initializing"
    sendEvent(name: "numberOfButtons", value: 1)				
    int disableTime = 1800
    if (enableDebug) {
        log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
        runIn(disableTime, debugOff)
    }
    refresh() // Get scene data
}

def debugOff() {
    log.warn("Disabling debug logging")
    device.updateSetting("enableDebug", [value:"false", type:"bool"])
}

// Probably won't happen but...
def parse(String description) {
    log.warn("Running unimplemented parse for: '${description}'")
}

/**
 * Parses Hue Bridge scene ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Scenes/HueSceneID", so just
 * looks for number after third "/" character
 */
def getHueDeviceNumber() {
    return device.deviceNetworkId.split("/")[3]
}

def on() {    
    logDebug("Turning on scene...")
    def data = parent.getBridgeData()
    def cmd = ["scene": getHueDeviceNumber()]
    def params = [
        uri: data.fullHost,
        path: "/api/${data.username}/groups/0/action",
        contentType: 'application/json',
        body: cmd
        ]
    asynchttpPut("parseBridgeResponse", params)
    logDebug("Command sent to Bridge: $cmd")
    doSendEvent("switch", "on", null)
}

def off() {
    logDebug("off()")
    doSendEvent("switch", "off", null)
    if (state.type == "GroupScene") {
        logDebug("Scene is GroupScene; turning off group $state.group")
        def dniParts = device.deviceNetworkId.split("/")
        def dni = "${dniParts[0]}/${dniParts[1]}/Group/${state.group}"
        def dev = parent.getChildDevice(dni)
        if (dev) {
            logDebug("Hubitat device for group ${state.group} found; turning off")
            dev.off()
        } else {
            logDebug("Device not found; sending command directly to turn off Hue group")
            def data = parent.getBridgeData()
            def cmd = ["on": "false"]
            def params = [
                uri: data.fullHost,
                path: "/api/${data.username}/groups/${state.group}/action",
                contentType: 'application/json',
                body: cmd
            ]
            asynchttpPut("parseBridgeResponse", params)
            logDebug("Command sent to Bridge: $cmd")
        }
    } else if (state.type == "LightScene") {
        logDebug("Scene is LightScene; turning off lights $state.lights")
        state.lights.each {
            def dniParts = device.deviceNetworkId.split("/")
            def dni = "${dniParts[0]}/${dniParts[1]}/Light/${it}"
            def dev = parent.getChildDevice(dni)
            if (dev) {
                logDebug("Hubitat device for light ${it} found; turning off")
                dev.off()
            } else {
                logDebug("Device not found; sending command directly to turn off Hue light")
                def data = parent.getBridgeData()
                def cmd = ["on": "false"]
                def params = [
                    uri: data.fullHost,
                    path: "/api/${data.username}/lights/${it}/state",
                    contentType: 'application/json',
                    body: cmd
                ]
                asynchttpPut("parseBridgeResponse", params)
                logDebug("Command sent to Bridge: $cmd")
            }
        }

    } else {
        log.warn "No off() action available for scene $device.displayName"
    }
}

def push(btnNum) {
    on()
    doSendEvent("pushed", "1", null)
}

def doSendEvent(eventName, eventValue, eventUnit) {
    logDebug("Creating event for $eventName...")
    def descriptionText = "${device.displayName} ${eventName} is ${eventValue}${eventUnit ?: ''}"
    logDesc(descriptionText)
    def event
    if (eventUnit) {
        event = sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText, unit: eventUnit) 
    } else {
        event = sendEvent(name: eventName, value: eventValue, descriptionText: descriptionText) 
    }
    return event
}

/** Gets data about scene from Bridge; does not update bulb/group status */
def refresh() {
    logDebug("Refresh...")
    def data = parent.getBridgeData()
    def sceneParams = [
        uri: data.fullHost,
        path: "/api/${data.username}/scenes/${getHueDeviceNumber()}",
        contentType: 'application/json',
        //body: cmds
        ]
    asynchttpGet("parseSceneAttributeResponse", sceneParams)  
}

def configure() {
    // Do I need to do anything here?
    log.warn "configure()"
}

/**
 * Parses data returned when getting scene data from Bridge
 */
def parseSceneAttributeResponse(resp, data) {
    logDebug("parseSceneAttributeResponse response from Bridge: $resp.status")
    def sceneAttributes
    try {
        sceneAttributes = resp.json
    } catch (ex) {
        log.error("Could not parse scene data: ${resp.errorMessage ?: ex}")
        return
    }
    if (sceneAttributes["type"] == "GroupScene") {
        state.type = "GroupScene"
        state.group = sceneAttributes["group"]
        state.remove("lights")
    } else if (sceneAttributes["type"] == "LightScene") {
        state.type = "LightScene"
        state.lights = sceneAttributes["lights"]
        state.remove("group")
    } else {
        log.warn "Unknown scene type; off() commands will not work"
        state.remove("group")
        state.remove("lights")
        state.remove("type")
    }
}

/**
 * Generic callback for async Bridge calls when we don't care about
 * the response (but can log it if debug enabled)
 */
def parseBridgeResponse(resp, data) {
    logDebug("Response from Bridge: $resp.status")
}

def logDebug(str) {
    if (settings.enableDebug) log.debug(str)
}

def logDesc(str) {
    if (settings.enableDesc) log.info(str)
}