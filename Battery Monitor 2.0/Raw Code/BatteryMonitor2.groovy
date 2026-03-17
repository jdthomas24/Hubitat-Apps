definition(
    name: "Battery Monitor 2.0",
    namespace: "jdthomas24",
    author: "Jdthomas24",
    description: "Advanced Hubitat battery monitoring with analytics, trends and replacement tracking.",
    category: "Convenience",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/examples/icons/battery.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/examples/icons/battery@2x.png",
    iconX3Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/examples/icons/battery@2x.png"
)

preferences {
    page(name:"mainPage")
    page(name:"summaryPage")
    page(name:"trendsPage")
    page(name:"historyPage")
    page(name:"replacementPage")
    page(name:"manualReplacementPage")
    page(name:"manualReplacementConfirmPage")
}

// =====================
// MAIN PAGE (PATCHED)
// Bolded top note; removed bottom note
// =====================
def mainPage(){
    dynamicPage(name:"mainPage",title:"Battery Monitor 2.0",install:true,uninstall:true){

        section("Auto Battery Discovery"){
            paragraph "**⚠ Important: The app will automatically detect all devices reporting battery levels. " +
                      "Please select the devices you want to actively monitor from the list below. " +
                      "Only selected devices will be tracked for trends, battery health, and notifications.**"
            input "autoDevices","capability.battery",
                  title:"Select battery devices to monitor",
                  multiple:true,
                  required:true
        }

        section("Battery Scan Interval"){
            input "scanInterval","enum",
            title:"Scan Frequency",
            options:["1":"Hourly","3":"Every 3 Hours","6":"Every 6 Hours"],
            defaultValue:"3"
        }

        section("Daily Report"){
            input "summaryTime","time",title:"Daily report time"
        }

        section("Notifications"){
            input "enablePush","bool",title:"Enable notifications",defaultValue:true
            input "notifyDevices","capability.notification",title:"Notification devices",multiple:true,required:false
        }

        section("Reports"){
            href "summaryPage",title:"Battery Summary"
            href "trendsPage",title:"Battery Trends"
            href "historyPage",title:"Battery Replacement History"
            href "replacementPage",title:"Manual Battery Replacement"
        }
    }
}

// =====================
// Existing initialization, scan, event handlers, and utility functions
// No changes here
// =====================
def installed(){ initialize() }
def updated(){ unsubscribe(); unschedule(); initialize() }

def initialize(){
    if(!state.history) state.history=[:]
    if(!state.trend) state.trend=[:]
    if(!state.replacements) state.replacements=[]

    subscribe(autoDevices,"battery",batteryHandler)

    scheduleScan()

    if(summaryTime){
        schedule(summaryTime,scheduledSummary)
    }

    autoDevices.each{ device ->
        if(!state.history[device.id]){
            def level=device.currentValue("battery")?.toInteger() ?:100
            state.history[device.id]=[
                lastLevel:level,
                lastDate:now(),
                drain:0.3,
                samples:[]
            ]
            state.trend[device.id]="Stable"
        }
    }
}

def scheduleScan(){
    switch(scanInterval){
        case "1": runEvery1Hour(scanBatteries); break
        case "3": runEvery3Hours(scanBatteries); break
        case "6": schedule("0 0 */6 ? * *",scanBatteries); break
    }
}

def scanBatteries(){
    autoDevices.each{ device ->
        def level=device.currentValue("battery")?.toInteger()
        if(level!=null){
            updateBattery(device,level)
        }
    }
}

def batteryHandler(evt){
    def level=evt.value?.toInteger()
    if(level!=null){
        updateBattery(evt.device,level)
    }
}

def updateBattery(device,level){
    def data=state.history[device.id]
    if(!data) return

    detectReplacement(device,level,data.lastLevel)

    def days=(now()-data.lastDate)/(1000*60*60*24)
    if(days>0){
        def drain=(data.lastLevel-level)/days
        if(drain>0){
            data.samples << drain
            if(data.samples.size()>5){
                data.samples.remove(0)
            }
            data.drain=data.samples.sum()/data.samples.size()
            updateTrend(device,data.drain)
        }
    }

    data.lastLevel=level
    data.lastDate=now()
}

def detectReplacement(device,newLevel,oldLevel){
    if(oldLevel==null) return
    if(newLevel>=95 && oldLevel<=40){
        state.replacements << [
            device:device.displayName,
            date:new Date().format("yyyy-MM-dd HH:mm",location.timeZone)
        ]
        state.history[device.id].drain=0.3
        state.trend[device.id]="Stable"
    }
}

def updateTrend(device,drain){
    if(drain<0.3) state.trend[device.id]="Stable"
    else if(drain<0.8) state.trend[device.id]="Moderate"
    else state.trend[device.id]="Heavy Drain"
}

def getDrain(device){ return state.history?.get(device.id)?.drain ?:0.3 }
def displayDrain(device){ return String.format("%.2f",getDrain(device)) }
def estDays(device){
    def level=device.currentValue("battery")?.toInteger() ?:100
    def drain=getDrain(device)
    if(drain<=0) drain=0.3
    return Math.round(level/drain)
}
def health(device){
    def drain=getDrain(device)
    if(drain<0.3) return "Excellent"
    if(drain<0.7) return "Good"
    if(drain<1.2) return "Fair"
    return "Poor"
}
def getBatteryLevelDisplay(level){
    if(level<=25) return "🔴 ${level}%"
    if(level<=70) return "🟠 ${level}%"
    if(level<100) return "🟢 ${level}%"
    return "💯 ${level}%"
}
def getLastReport(device){
    def last = state.history?.get(device.id)?.lastDate
    if(!last) return "Never"
    def diff = now() - last
    def mins = (diff / 60000).toInteger()
    def hrs = (mins / 60).toInteger()
    def days = (hrs / 24).toInteger()
    if(days>0) return "${days}d ago"
    if(hrs>0) return "${hrs}h ago"
    if(mins>0) return "${mins}m ago"
    return "Just now"
}

def summaryPage(){
    dynamicPage(name:"summaryPage",title:"Battery Summary",install:false){
        section("Battery Summary"){

            def devs = autoDevices.findAll{ it.currentValue("battery") != null }
            devs = devs.sort{ a,b -> a.currentValue("battery") <=> b.currentValue("battery") }

            if(!devs){ paragraph "No battery devices found."; return }

            def table="<table style='width:100%; border-collapse: collapse;'>"
            table+="<tr style='font-weight:bold;'><td>Device</td><td>Battery</td><td>Drain</td><td>Est Days</td><td>Health</td><td>Last Report</td></tr>"

            devs.each{ device ->
                def level=device.currentValue("battery")?.toInteger()
                def color=getBatteryLevelDisplay(level)
                table+="<tr>"
                table+="<td>${level<=25?'⚠️ ':''}${device.displayName}</td>"
                table+="<td>${color}</td>"
                table+="<td>${String.format('%.2f',getDrain(device))}</td>"
                table+="<td>${estDays(device)}</td>"
                table+="<td>${health(device)}</td>"
                table+="<td>${getLastReport(device)}</td>"
                table+="</tr>"
            }
            table+="</table>"

            paragraph table
        }
    }
}

def trendsPage(){
    dynamicPage(name:"trendsPage",title:"Battery Trends",install:false){
        section("Battery Trend Analysis"){

            // Clarified trend note
            paragraph "⚠ Note: Trends may be overestimated until the device reports at least 5 battery events. " +
                      "Trend accuracy improves as more data is collected over time."

            def devs = autoDevices.findAll{ it.currentValue("battery") != null }
            if(!devs){ paragraph "No battery devices."; return }

            def table="<table style='width:100%; border-collapse: collapse;'>"
            table+="<tr style='font-weight:bold;'><td>Device</td><td>Battery</td><td>Drain/day</td><td>Trend</td></tr>"

            devs.each{ device ->
                def level=device.currentValue("battery")?.toInteger()
                def color=getBatteryLevelDisplay(level)
                table+="<tr>"
                table+="<td>${device.displayName}</td>"
                table+="<td>${color}</td>"
                table+="<td>${String.format('%.2f',getDrain(device))}</td>"
                table+="<td>${state.trend?.get(device.id) ?: 'Stable'}</td>"
                table+="</tr>"
            }

            table+="</table>"
            paragraph table
        }
    }
}

def historyPage(){
    dynamicPage(name:"historyPage",title:"Battery Replacement History",install:false){
        section("Replacement Log"){
            def replacements = state.replacements ?: []
            if(replacements.size()==0){ paragraph "No battery replacements detected." }
            else{ replacements.reverse().each{ paragraph "${it.device} - ${it.date}" } }
        }
    }
}

def replacementPage(){
    dynamicPage(name:"replacementPage",title:"Battery Replacement",install:false){
        section("Manual Replacement"){
            input "replaceDevice","enum",title:"Select Device",options:autoDevices.collectEntries{[(it.id):it.displayName]},required:false
            input "replaceConfirm","bool",title:"Confirm Battery Replaced",required:false
        }
        section("Replacement History"){
            state.replacements.reverse().each{ paragraph "${it.date} — ${it.device}" }
        }
    }
}

// =====================
// Patched daily report
// Only change in scheduledSummary() section
// =====================
def scheduledSummary(){

    def devices = autoDevices.collect{ d ->
        [device: d, level: d.currentValue("battery")?.toInteger()]
    }.findAll{ it.level != null }

    devices = devices.sort{ it.level }

    def critical = []
    def good = []
    def full = []

    devices.each{ entry ->
        if(entry.level <= 25) critical << entry
        else if(entry.level == 100) full << entry
        else good << entry
    }

    def report = "Battery Monitor Daily Report\n\n"
    report += "Critical (≤25%): ${critical.size()}\n"
    report += "Good (26-99%): ${good.size()}\n"
    report += "Full (100%): ${full.size()}\n\n"

    if(critical){
        report += "🔴 Critical Devices\n"
        critical.each{ report += "${it.device.displayName} : ${it.level}%\n" }
        report += "\n"
    }

    if(good){
        report += "🟡 Good Devices\n"
        good.each{ report += "${it.device.displayName} : ${it.level}%\n" }
        report += "\n"
    }

    if(full){
        report += "🟢 Full Devices\n"
        full.each{ report += "${it.device.displayName} : ${it.level}%\n" }
    }

    def notifyList = notifyDevices?.unique{ it.id }
    if(enablePush && notifyList){
        notifyList.each{
            it.deviceNotification(report)
        }
    }
}
