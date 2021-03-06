/*
 * =============================  CoCoHue Group (Driver) ===============================
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
 *  Last modified: 2020-01-22
 * 
 *  Changelog:
 * 
 *  v1.0 - Initial Release
 *  v1.1 - Added parity with bulb features (effects, etc.)
 *  v1.5 - Group switch/level/etc. states now propagated to member bulbs w/o polling
 *  v1.5b - Eliminated duplicate color/CT events on refresh
 *  v1.6b - Changed bri_inc to match Hubitat behavior
 *  v1.7 - Bulb switch/level states now propgate to groups w/o polling
 *  v1.7b - Modified startLevelChange behavior to avoid possible problems with third-party devices
 *  v1.8 - Changed effect state to custom attribute instead of colorMode
 *         Added ability to disable group->bulb state propagation;
 *         Removed ["alert:" "none"] from on() command, now possible explicitly with flashOff() 
 *  v1.8b - Skip spurious color name event if bulb not in correct mode 
 *  v1.8c - Added back color/CT events for manual commands not from bridge without polling
 *  v1.9 - Parse xy as ct (previously did rgb but without parsing actual color)
 *
 */ 

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static Map lightEffects = [0: "None", 1:"Color Loop"]

metadata {
    definition (name: "CoCoHue Group", namespace: "RMoRobert", author: "Robert Morris", importUrl: "https://raw.githubusercontent.com/HubitatCommunity/CoCoHue/master/drivers/cocohue-group-driver.groovy") {
        capability "Actuator"
        capability "Color Control"
        capability "Color Temperature"
        capability "Configuration"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        capability "ChangeLevel"
        capability "Light"
        capability "ColorMode"
        capability "LightEffects"

        command "flash"
        command "flashOnce"
        command "flashOff"
        
        attribute "colorName", "string"
        attribute "effect", "string"
    }
       
   preferences {
        input(name: "transitionTime", type: "enum", description: "", title: "Transition time", options:
            [[0:"ASAP"],[400:"400ms"],[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 400)
        input(name: "hiRezHue", type: "bool", title: "Enable hue in degrees (0-360 instead of 0-100)", defaultValue: false)
        input(name: "colorStaging", type: "bool", description: "", title: "Enable color pseudo-prestaging", defaultValue: false)
        input(name: "levelStaging", type: "bool", description: "", title: "Enable level pseudo-prestaging", defaultValue: false)
        input(name: "updateBulbs", type: "bool", description: "", title: "Update member bulb states immediately when group state changes",
            defaultValue: true)
        input(name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true)
        input(name: "enableDesc", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
    }
}

def installed(){
    log.debug "Installed..."
    def le = new groovy.json.JsonBuilder(lightEffects)
    sendEvent(name: "lightEffects", value: le)
    initialize()
}

def updated(){
    log.debug "Updated..."
    initialize()
}

def initialize() {
    log.debug "Initializing"
    int disableTime = 1800
    if (enableDebug) {
        log.debug "Debug logging will be automatically disabled in ${disableTime} seconds"
        runIn(disableTime, debugOff)
    }
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
 * Parses Hue Bridge device ID number out of Hubitat DNI for use with Hue API calls
 * Hubitat DNI is created in format "CCH/BridgeMACAbbrev/Group/HueDeviceID", so just
 * looks for number after third "/" character
 */
def getHueDeviceNumber() {
    return device.deviceNetworkId.split("/")[3]
}

def on() {    
    logDebug("Turning on...")
    /* TODO: Add setting for "agressive" vs. normal prestaging (?), and for regular pre-staging,
     check if current level is different from lastXYZ value, in which case it was probably
     changed outside of Hubitat and we should not set the pre-staged value(s)--Hue does not
     support "true" prestaging, so any prestaging is a Hubitat-only workaround */
    addToNextBridgeCommand(["on": true], !(colorStaging || levelStaging))
    sendBridgeCommand()
    state.remove("lastHue")
    state.remove("lastSat")
    state.remove("lastCT")
    state.remove("lastLevel")
}

def off() {    
    logDebug("Turning off...")
    state.remove("lastHue")
    state.remove("lastSat")
    state.remove("lastCT")
    state.remove("lastLevel")
    addToNextBridgeCommand(["on": false], true)
    sendBridgeCommand()
}


def startLevelChange(direction) {
    logDebug("Running startLevelChange($direction)...")
    def cmd = ["bri": (direction == "up" ? 254 : 1), "transitiontime": 30]
    sendBridgeCommand(cmd, false) 
}

def stopLevelChange() {
    logDebug("Running stopLevelChange...")
    def cmd = ["bri_inc": 0]
    sendBridgeCommand(cmd, false) 
}

def setLevel(value) {
    setLevel(value, ((transitionTime != null ? transitionTime.toBigDecimal() : 1000)) / 1000)
}

def setLevel(value, rate) {
    logDebug("Setting level to ${value}% over ${rate}s...")
    state.remove("lastLevel")
    if (value < 0) value = 1
    else if (value > 100) value = 100
    else if (value == 0) {
        off()
        logDebug("Level is 0 so turning off instead")
        return
    }
    def newLevel = scaleBriToBridge(value)
    def scaledRate = (rate * 10).toInteger()
    addToNextBridgeCommand(["bri": newLevel, "transitiontime": scaledRate], !(levelStaging || colorStaging))
    def isOn = device.currentValue("switch") == "on"    
    if (!levelStaging || isOn) {
        addToNextBridgeCommand(["on": true])
        sendBridgeCommand()
    } else {
        state["lastLevel"] = device.currentValue("level")
        createEventsFromMap()
    }
}

def setColorTemperature(value) {
    logDebug("Setting color temperature to $value...")
    state.remove("lastHue")
    state.remove("lastSat")
    state.remove("lastCT")
    def newCT = Math.round(1000000/value)
    if (newCT < 153) value = 153
    else if (newCT > 500) newCT = 500
    def scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger()
    addToNextBridgeCommand(["ct": newCT, "transitiontime": scaledRate], !(levelStaging || colorStaging))
    def isOn = device.currentValue("switch") == "on"    
    if (!colorStaging || isOn) {
        addToNextBridgeCommand(["on": true])
        sendBridgeCommand()
    } else {
        state["lastCT"] = device.currentValue("colorTemperature")
        createEventsFromMap()
    }
}

def setColor(value) {
    logDebug("Setting color...")
    if (value.hue == null || value.hue == "NaN" || value.saturation == null || value.saturation == "NaN") {
        logDebug("Exiting setColor because no hue and/or saturation set")
        return
    }
    def newHue = scaleHueToBridge(value.hue)
    def newSat = scaleSatToBridge(value.saturation)
    def newBri = (value.level != null && value.level != "NaN") ? scaleBriToBridge(value.level) : null
    state.remove("lastHue")
    state.remove("lastSat")
    state.remove("lastCT")
    if (newBri) state.remove("lastLevel")
    def scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger()
    addToNextBridgeCommand(["hue": newHue, "sat": newSat, "transitiontime": scaledRate], , !(levelStaging || colorStaging))
    if (newBri) addToNextBridgeCommand(["bri": newBri])
    def isOn = device.currentValue("switch") == "on"    
    if (!colorStaging || isOn) {
        addToNextBridgeCommand(["on": true])
        if (newBri) addToNextBridgeCommand(["bri": newBri])
        sendBridgeCommand()
    } else {
        state["lastHue"] = device.currentValue("hue")
        state["lastSat"] = device.currentValue("saturation")
        if (newBri) state["lastLevel"] = device.currentValue("level")
        createEventsFromMap()
    }
}

def setHue(value) {
    logDebug("Setting hue...")
    def newHue = scaleHueToBridge(value)
    state.remove("lastHue")
    state.remove("lastCT")
    def scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger()    
    addToNextBridgeCommand(["hue": newHue, "transitiontime": scaledRate], , !(levelStaging || colorStaging))
    def isOn = device.currentValue("switch") == "on"    
    if (!colorStaging || isOn) {
        addToNextBridgeCommand(["on": true])
        sendBridgeCommand()
    } else {
        state["lastHue"] = device.currentValue("hue")
        createEventsFromMap()
    }
}

def setSaturation(value) {
    logDebug("Setting saturation...")
    def newSat = scaleSatToBridge(value)
    state.remove("lastSat")
    state.remove("lastCT")
    def scaledRate = ((transitionTime != null ? transitionTime.toBigDecimal() : 1000) / 100).toInteger()
    addToNextBridgeCommand(["sat": newSat, "transitiontime": scaledRate], !(levelStaging || colorStaging))
    def isOn = device.currentValue("switch") == "on"    
    if (!colorStaging || isOn) {
        addToNextBridgeCommand(["on": true])
        sendBridgeCommand()
    } else {
        state["lastSat"] = device.currentValue("saturation")
        createEventsFromMap()
    }
}

def setEffect(String effect) {
    def id = lightEffects.find { it.value == effect }
    if (id != null) setEffect(id.key)
}

def setEffect(id) {
    logDebug("Setting effect $id...")
    state.remove("lastHue")
    // May want to see if it really makes sense to remove these too:
    state.remove("lastSat")
    state.remove("lastCT")
    addToNextBridgeCommand(["effect": (id == 1 ? "colorloop" : "none"), "on": true], true)
    // No prestaging implemented here
    sendBridgeCommand()
}

def setNextEffect() {
    def currentEffect = state.crntEffectId ?: 0
    currentEffect++
    if (currentEffect > 1) currentEffect = 0
    setEffect(currentEffect)
}

def setPreviousEffect() {
    def currentEffect = state.crntEffectId ?: 0
    currentEffect--
    if (currentEffect < 0) currentEffect = 1
    setEffect(currentEffect)
}

def flash() {
    logDesc("${device.displayName} started 15-cycle flash")
    def cmd = ["alert": "lselect"]
    sendBridgeCommand(cmd, false) 
}

def flashOnce() {
    logDesc("${device.displayName} started 1-cycle flash")
    def cmd = ["alert": "select"]
    sendBridgeCommand(cmd, false) 
}

def flashOff() {
    logDesc("${device.displayName} was sent command to stop flash")
    def cmd = ["alert": "none"]
    sendBridgeCommand(cmd, false) 
}

/**
 * Used to build body of (future) HTTP PUT to Bridge; useful to build up
 * parts a command in multiple places/methods and then send to Bridge as one
 * command (e.g., if "on" with lots of prestaging enabled and set) to maximize
 * efficiency and provide one transition instead of multiple.
 * @param cmdToAdd Map of Bridge commands to place in next command to be sent--example: ["on": true]
 * @param clearFirst If true (optional; default is false), will clear pending command map first
 */
def addToNextBridgeCommand(Map cmdToAdd, boolean clearFirst=false) {
    if (clearFirst || !state.nextCmd) state.nextCmd = [:]
    state.nextCmd << cmdToAdd
}

/**
 * Iterates over Hue light state commands/states in Hue format (e.g., ["on": true]) and does
 * a sendEvent for each relevant attribute; intended to be called either when commands are sent
 * to Bridge or if pre-staged attribute is changed and "real" command not yet able to be sent, or
 * to parse/update light states based on data received from Bridge
 * @param bridgeMap Map of light states that are or would be sent to bridge OR state as received from
 *  Bridge; defaults to the  state attribute created by addToNextBridgeCommand if not provided
 * @param isFromBridge Set to true if this is data read from Hue Bridge rather than intended to be sent
 *  to Bridge; if true, will ignore differences for prestaged attributes if switch state is off
 */
def createEventsFromMap(Map bridgeCommandMap = state.nextCmd, boolean isFromBridge = false) {
    if (!bridgeCommandMap) {
        logDebug("createEventsFromMap called but map command empty; exiting")
        return
    }
    def bridgeMap = bridgeCommandMap
    logDebug("Preparing to create events from map${isFromBridge ? ' from Bridge' : ''}: ${bridgeMap}")
    def eventName, eventValue, eventUnit, descriptionText
    def colorMode = bridgeMap["colormode"]
    if (isFromBridge && bridgeMap["colormode"] == "xy") {
        colorMode == "ct"
        logDebug("In XY mode but parsing as CT")
    }
    boolean isOn = bridgeMap["any_on"]
    bridgeMap.each {
        switch (it.key) {
            case "on":
                if (isFromBridge) break
            case "any_on":
                eventName = "switch"
                eventValue = it.value ? "on" : "off"
                eventUnit = null                
                if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
                break
            case "bri":
                eventName = "level"
                eventValue = scaleBriFromBridge(it.value)
                eventUnit = "%"
                if (device.currentValue(eventName) != eventValue) {
                    if (!isOn && isFromBridge && levelStaging && state.nextCmd?.get("bri")) {
                        logDebug("Prestaging enabled, light off, and prestaged command found; not sending ${eventName} event")
                        break
                    }
                    doSendEvent(eventName, eventValue, eventUnit)                    
                }
                break
            case "colormode":
                eventName = "colorMode"
                eventValue = (it.value == "hs" ? "RGB" : "CT")
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) {
                    if (!isOn && isFromBridge && colorStaging && (state.nextCmd?.get("hue") || state.nextCmd?.get("sat") || state.nextCmd?.get("ct"))) {
                        logDebug("Prestaging enabled, light off, and prestaged command found; not sending ${eventName} event")
                        break
                    }
                    doSendEvent(eventName, eventValue, eventUnit)
                }
                break
            case "ct":
                eventName = "colorTemperature"
                eventValue = Math.round(1000000/it.value)
                eventUnit = "K"
                if (device.currentValue(eventName) != eventValue) {
                    if (!isOn && isFromBridge && colorStaging && (state.nextCmd?.get("hue") || state.nextCmd?.get("sat") || state.nextCmd?.get("ct"))) {
                        logDebug("Prestaging enabled, light off, and prestaged command found; not sending ${eventName} event")
                        break
                    } else if (isFromBridge && colorMode == "hs") {
                        logDebug("Skipping colorTemperature event creation because light not in ct mode")
                        break
                    }
                    doSendEvent(eventName, eventValue, eventUnit)
                }
                if (isFromBridge && colorMode == "hs") break
                setGenericTempName(eventValue)
                eventName = "colorMode"
                eventValue = "CT"
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
                break
            case "hue":
                eventName = "hue"
                eventValue = scaleHueFromBridge(it.value)
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) {
                    if (!isOn && isFromBridge && colorStaging && (state.nextCmd?.get("hue") || state.nextCmd?.get("sat") || state.nextCmd?.get("ct"))) {
                        logDebug("Prestaging enabled, light off, and prestaged command found; not sending ${eventName} event")
                        break
                    }
                    doSendEvent(eventName, eventValue, eventUnit)
                }
                if (isFromBridge && colorMode != "hs") {
                        logDebug("Skipping colorMode and color name event creation because light not in hs mode")
                        break
                }
                setGenericName(eventValue)
                if (isFromBridge) break
                eventName = "colorMode"
                eventValue = "RGB"
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
                break    
            case "sat":
                eventName = "saturation"
                eventValue = scaleSatFromBridge(it.value)
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) {
                    if (!isOn && isFromBridge && colorStaging && (state.nextCmd?.get("hue") || state.nextCmd?.get("sat") || state.nextCmd?.get("ct"))) {
                        logDebug("Prestaging enabled, light off, and prestaged command found; not sending ${eventName} event")
                        break
                    }
                    doSendEvent(eventName, eventValue, eventUnit)
                }
                if (isFromBridge) break
                eventName = "colorMode"
                eventValue = "RGB"
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
                break
            case "effect":
                eventName = "effect"
                eventValue = (it.value == "colorloop" ? "colorloop" : "none")
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) {
                    doSendEvent(eventName, eventValue, eventUnit)
                }
                eventUnit = null
                if (device.currentValue(eventName) != eventValue) doSendEvent(eventName, eventValue, eventUnit)
                break
            case "transitiontime":
            case "mode":
            case "alert":
                break
            default:
                break
                //log.warn "Unhandled key/value discarded: $it"
        }
    }
}

/**
 * Sends HTTP PUT to Bridge using the either command map previously built
 * with one or more calls to addToNextBridgeCommand and clears
 * that map (this is normally the case) or sends custom map without changing this
 * map (useful for one-off Hubitat commands like start/stopLevelChange)
 * @param customMap If provided, uses this map instead of the one previously built =
 *        with addToNextBridgeCommand; if not provided, uses and then clears the
 *        previously built map
 * @param createHubEvents Will iterate over Bridge command map and do sendEvent for all
 *        affected device attributes (e.g., will send an "on" event for "switch" if map contains "on": true)
 */
def sendBridgeCommand(Map customMap = null, boolean createHubEvents=true) {    
    logDebug("Sending command to Bridge: ${customMap ?: state.nextCmd}")
    Map cmd = [:]
    if (customMap != null) {
        cmd = customMap
    } else {
        cmd = state.nextCmd
        state.remove("nextCmd")
    }
    
    if (!cmd) {
        log.debug("Commands not sent to Bridge because command map empty")
        return
    }
    if (createHubEvents) createEventsFromMap(cmd)
    def data = parent.getBridgeData()
    def params = [
        uri: data.fullHost,
        path: "/api/${data.username}/groups/${getHueDeviceNumber()}/action",
        contentType: 'application/json',
        body: cmd
        ]
    asynchttpPut("parseBridgeResponse", params)
    if ((cmd.containsKey("on") || cmd.containsKey("bri")) && settings["updateBulbs"]) {
        parent.updateMemberBulbStatesFromGroup(cmd, state.memberBulbs)
    }
    logDebug("---- Command sent to Bridge! ----")
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

def refresh() {
    log.warn "Refresh CoCoHue Bridge device instead of individual device to update (all) bulbs/groups"
}

def configure() {
    // Do I need to do anything here?
    log.warn "configure()"
}

// Hubiat-provided color/name mappings
def setGenericName(hue){
    def colorName
    hue = hue.toInteger()
    if (!hiRezHue) hue = (hue * 3.6)
    switch (hue.toInteger()){
        case 0..15: colorName = "Red"
            break
        case 16..45: colorName = "Orange"
            break
        case 46..75: colorName = "Yellow"
            break
        case 76..105: colorName = "Chartreuse"
            break
        case 106..135: colorName = "Green"
            break
        case 136..165: colorName = "Spring"
            break
        case 166..195: colorName = "Cyan"
            break
        case 196..225: colorName = "Azure"
            break
        case 226..255: colorName = "Blue"
            break
        case 256..285: colorName = "Violet"
            break
        case 286..315: colorName = "Magenta"
            break
        case 316..345: colorName = "Rose"
            break
        case 346..360: colorName = "Red"
            break
        default: colorName = "undefined" // shouldn't happen, but just in case
            break            
    }
    if (device.currentValue("colorName") != colorName) doSendEvent("colorName", colorName, null)
}

// Hubitat-provided ct/name mappings
def setGenericTempName(temp){
    if (!temp) return
    def genericName
    def value = temp.toInteger()
    if (value <= 2000) genericName = "Sodium"
    else if (value <= 2100) genericName = "Starlight"
    else if (value < 2400) genericName = "Sunrise"
    else if (value < 2800) genericName = "Incandescent"
    else if (value < 3300) genericName = "Soft White"
    else if (value < 3500) genericName = "Warm White"
    else if (value < 4150) genericName = "Moonlight"
    else if (value <= 5000) genericName = "Horizon"
    else if (value < 5500) genericName = "Daylight"
    else if (value < 6000) genericName = "Electronic"
    else if (value <= 6500) genericName = "Skylight"
    else if (value < 20000) genericName = "Polar"
    if (device.currentValue("colorName") != genericName) doSendEvent("colorName", genericName, null)
}

/**
 * Generic callback for async Bridge calls when we don't care about
 * the response (but can log it if debug enabled)
 */
def parseBridgeResponse(resp, data) {
    logDebug("Response from Bridge: $resp.status")
}

/**
 * Scales Hubitat's 1-100 brightness levels to Hue Bridge's 1-254
 */
private scaleBriToBridge(hubitatLevel) {
    def scaledLevel =  hubitatLevel == 1 ? 1 : hubitatLevel.toBigDecimal() / 100 * 254
    return Math.round(scaledLevel)
}

/**
 * Scales Hue Bridge's 1-254 brightness levels to Hubitat's 1-100
 */
private scaleBriFromBridge(bridgeLevel) {
    def scaledLevel = bridgeLevel.toBigDecimal() / 254 * 100
    if (scaledLevel < 1) scaledLevel = 1
    return Math.round(scaledLevel)
}

private scaleHueToBridge(hubitatLevel) {
    def scaledLevel = Math.round(hubitatLevel.toBigDecimal() / (hiRezHue ? 360 : 100) * 65535)
    if (scaledLevel < 0) scaledLevel = 0
    else if (scaledLevel > 65535) scaledLevel = 65535
    return scaledLevel
}

private scaleHueFromBridge(bridgeLevel) {
    def scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 65535 * (hiRezHue ? 360 : 100))
    if (scaledLevel < 0) scaledLevel = 0
    else if (scaledLevel > 360) scaledLevel = 360
    else if (scaledLevel > 100 && !hiRezHue) scaledLevel = 100
    return scaledLevel
}

private scaleSatToBridge(hubitatLevel) {
    def scaledLevel = Math.round(hubitatLevel.toBigDecimal() / 100 * 254)
    if (scaledLevel < 0) scaledLevel = 0
    else if (scaledLevel > 254) scaledLevel = 254
    return scaledLevel
    return scaleHueFromBridge()
}

private scaleSatFromBridge(bridgeLevel) {
    def scaledLevel = Math.round(bridgeLevel.toBigDecimal() / 254 * 100)
    if (scaledLevel < 0) scaledLevel = 0
    else if (scaledLevel > 100) scaledLevel = 100
    return scaledLevel
}

/**
 *  Sets state.memberBulbs to IDs of bulbs contained in this group; used to manipulate CoCoHue member
 *  bulb states (e.g., on, off, level, etc.) when group state changed so this info propogates faster than
 *  polling (or if polling disabled)
 */ 
def setMemberBulbIDs(List ids) {
    state.memberBulbs = ids
}

/**
 *  Returns Hue IDs of member bulbs (see setMemberBulbIDs for use case; exposed for use by bridge child app)
 */
def getMemberBulbIDs() {
    return state.memberBulbs
}

def logDebug(str) {
    if (settings.enableDebug) log.debug(str)
}

def logDesc(str) {
    if (settings.enableDesc) log.info(str)
}