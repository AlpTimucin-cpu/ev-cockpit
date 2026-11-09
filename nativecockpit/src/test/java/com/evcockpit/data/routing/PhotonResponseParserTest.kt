package com.evcockpit.data.routing
import org.junit.Assert.*
import org.junit.Test
class PhotonResponseParserTest {
    @Test fun geoJsonOrderIsLongitudeLatitude(){
        val json="""{"features":[{"geometry":{"coordinates":[30.704,36.885]},"properties":{"name":"Antalya","state":"Antalya"}}]}"""
        val result=PhotonResponseParser.parse(json).single()
        assertEquals(36.885,result.coordinate.latitude,0.000001)
        assertEquals(30.704,result.coordinate.longitude,0.000001)
    }
    @Test fun emptyResultsRemainEmpty(){
        assertTrue(PhotonResponseParser.parse("""{"features":[]}""").isEmpty())
    }
}
