package com.evcockpit.mediahub

/**
 * Defensive, opt-in Car Scanner CSV inspection. Never reads the car or
 * connects to its CAN/OBD bus. Only parses a file selected by the user.
 * VIN, GPS, coordinates, device IDs and full source CSVs are never retained.
 */
object CarScannerCsv {
    data class Reading(val socPercent: Double?, val odometerKm: Double?,
        val batteryVoltageV: Double?, val batteryTemperatureC: Double?,
        val dcPowerKw: Double?, val columnNames: List<String>, val inspectedRows: Int) {
        fun detail(): String = buildString {
            if(socPercent!=null) append("SoC: ").append(socPercent).append(" %\n")
            if(odometerKm!=null) append("Kilometre: ").append(odometerKm).append(" km\n")
            if(batteryVoltageV!=null) append("Batarya gerilimi: ").append(batteryVoltageV).append(" V\n")
            if(batteryTemperatureC!=null) append("Batarya sıcaklığı: ").append(batteryTemperatureC).append(" °C\n")
            if(dcPowerKw!=null) append("Güç: ").append(dcPowerKw).append(" kW\n")
            append("Taranan satır: ").append(inspectedRows)
        }
        fun hasMeasurements() =
            socPercent != null || odometerKm != null || batteryVoltageV != null ||
            batteryTemperatureC != null || dcPowerKw != null
    }

    /** No raw URL or log location data gets written to SharedPreferences. */
    fun parse(raw: String): Reading {
        require(raw.length <= 3_500_000) { "CSV too large" }
        val lines=raw.lineSequence().filter { it.isNotBlank() }.take(8001).toList()
        if(lines.isEmpty()) return Reading(null,null,null,null,null,emptyList(),0)
        val head=lines.first().removePrefix("\uFEFF")
        val divider=listOf(';',',','\t').maxByOrNull { it -> head.count { c -> c==it } } ?: ';'
        val headers=splitRow(head,divider).take(150).map { normalize(it) }
        fun locate(vararg names:String):Int =
            headers.indexOfFirst { cell -> names.any { token -> cell.contains(token) } }
        val idxSoc=locate("stateofcharge","batterypercent","batterysoc","soc")
        val idxKm=locate("odometer","mileage","kilometre","kilometer","kmstand")
        val idxVolts=locate("batteryvoltage","tractionbatteryvoltage","packvoltage","hvvoltage")
        val idxTemp=locate("batterytemperature","batterytemp","packtemp","batterythermal")
        val idxPower=locate("chargingpower","dcchargingpower","batterypower","batterykw")
        var soc:Double?=null
        var km:Double?=null
        var volts:Double?=null
        var temp:Double?=null
        var power:Double?=null
        var inspected=0
        for(line in lines.drop(1).take(8000)) {
            val cols=splitRow(line,divider)
            inspected++
            fun value(index:Int,min:Double,max:Double):Double? {
                if(index !in cols.indices)return null
                val cell=cols[index].trim().replace("%","").replace("V","")
                    .replace("kW","").replace("°C","").trim()
                val n=cell.replace(',','.').toDoubleOrNull() ?: return null
                return n.takeIf { it in min..max }
            }
            soc=value(idxSoc,0.0,100.0) ?: soc
            km=value(idxKm,0.0,2_000_000.0) ?: km
            volts=value(idxVolts,0.0,1000.0) ?: volts
            temp=value(idxTemp,-50.0,120.0) ?: temp
            power=value(idxPower,-600.0,600.0) ?: power
        }
        // Field names are kept only within this temporary Reading; no raw file stored.
        return Reading(soc,km,volts,temp,power,headers,inspected)
    }

    private fun normalize(value:String):String =
        value.lowercase()
            .replace("ı","i").replace("İ","i")
            .replace("ş","s").replace("ğ","g")
            .replace("ü","u").replace("ö","o").replace("ç","c")
            .filter { it.isLetterOrDigit() }

    /** Basic quoted CSV; delimited values with embedded commas are respected. */
    internal fun splitRow(line:String, delimiter:Char):List<String> {
        val fields=ArrayList<String>()
        val buffer=StringBuilder()
        var quote=false
        var i=0
        while(i<line.length && fields.size<200) {
            val c=line[i]
            when {
                c=='"' && quote && i+1<line.length && line[i+1]=='"' -> {
                    buffer.append('"');i++
                }
                c=='"' -> quote = !quote
                c==delimiter && !quote -> {fields.add(buffer.toString());buffer.setLength(0)}
                else -> buffer.append(c)
            }
            i++
        }
        fields.add(buffer.toString())
        return fields
    }
}
