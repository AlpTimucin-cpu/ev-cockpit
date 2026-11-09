package com.evcockpit.data.routing

import com.google.gson.JsonParser
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.RoutePlace

object PhotonResponseParser {
    fun parse(json:String):List<RoutePlace> {
        require(json.length<=600_000){"Address response is too large"}
        val root=JsonParser.parseString(json).asJsonObject
        val features=root.getAsJsonArray("features")?:return emptyList()
        return features.take(10).mapNotNull { el ->
            try{
                val item=el.asJsonObject
                val pair=item.getAsJsonObject("geometry")?.getAsJsonArray("coordinates")
                    ?:return@mapNotNull null
                val coordinate=GeoCoordinate(pair[1].asDouble,pair[0].asDouble)
                if(!coordinate.valid() || coordinate.latitude !in 35.0..43.0 ||
                   coordinate.longitude !in 25.0..46.0)return@mapNotNull null
                val props=item.getAsJsonObject("properties") ?:return@mapNotNull null
                val parts=listOf("name","street","city","state").mapNotNull { key->
                    props.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString?.trim()?.takeIf { it.isNotBlank()&&it.length<=90 }
                }.distinct()
                val name=parts.joinToString(", ").take(145).takeIf {it.isNotBlank()}
                    ?:return@mapNotNull null
                RoutePlace(name,coordinate)
            }catch(_:Exception){null}
        }.distinctBy { it.label to it.coordinate }.take(5)
    }
}
