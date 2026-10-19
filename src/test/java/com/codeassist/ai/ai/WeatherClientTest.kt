package com.codeassist.ai.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class WeatherClientTest {
    private val geo = """{"results":[{"id":1275339,"name":"Mumbai","latitude":19.07283,"longitude":72.88261,"country":"India"}]}"""
    private val fc = """{"current":{"time":"2026-10-10T12:00","temperature_2m":31.4,"apparent_temperature":35.2,""" +
        """"relative_humidity_2m":70,"weather_code":3,"wind_speed_10m":12.3}}"""

    @Test fun placeIsReadFromTheGeocodingAnswer() {
        val p = WeatherClient.parsePlace(geo)
        assertNotNull(p)
        assertEquals("Mumbai", p!!.name)
        assertEquals("India", p.country)
        assertEquals(19.07283, p.lat, 0.00001)
    }

    @Test fun noMatchOrBrokenAnswerGivesNoPlace() {
        assertNull(WeatherClient.parsePlace("""{"generationtime_ms":0.4}"""))
        assertNull(WeatherClient.parsePlace("""{"results":[]}"""))
        assertNull(WeatherClient.parsePlace("<html>502</html>"))
        assertNull(WeatherClient.parsePlace("""{"results":[{"name":"X","latitude":"north","longitude":1}]}"""))
        assertNull(WeatherClient.parsePlace("""{"results":[{"name":"X","latitude":95,"longitude":1}]}"""))
    }

    @Test fun currentValuesAreRead() {
        val n = WeatherClient.parseNow(fc)
        assertNotNull(n)
        assertEquals(31.4, n!!.tempC, 0.001)
        assertEquals(70, n.humidity)
        assertEquals(3, n.code)
        assertEquals(12.3, n.windKmh!!, 0.001)
    }

    @Test fun missingTemperatureOrAbsurdValueIsRefused() {
        assertNull(WeatherClient.parseNow("""{"current":{"weather_code":3}}"""))
        assertNull(WeatherClient.parseNow("""{"current":{"temperature_2m":99,"weather_code":3}}"""))
        assertNull(WeatherClient.parseNow("""{"hourly":{}}"""))
        assertNull(WeatherClient.parseNow("not json"))
    }

    @Test fun replyIsBuiltFromNumbersOnly() {
        val p = WeatherClient.parsePlace(geo)!!
        val n = WeatherClient.parseNow(fc)!!
        assertEquals(
            "Mumbai mein abhi 31°C hai (mehsoos 35°C), badal chhaye hain, humidity 70%, hawa 12 km/h.",
            WeatherClient.reply(p, n)
        )
    }

    @Test fun feelsLikeIsLeftOutWhenItIsClose() {
        val p = WeatherClient.Place("Pune", "India", 18.5, 73.8)
        val n = WeatherClient.Now(25.0, 25.5, null, null, 0)
        assertEquals("Pune mein abhi 25°C hai, aasman saaf hai.", WeatherClient.reply(p, n))
    }

    @Test fun placeNamesAreCleaned() {
        val name = WeatherClient.cleanName("Mumbai<script>alert(1)</script>")
        assertFalse(name.contains('<') || name.contains('('))
        assertTrue(WeatherClient.cleanName("x".repeat(100)).length <= 40)
        assertEquals("St. John's", WeatherClient.cleanName("St. John's"))
    }

    @Test fun urlsAreEncodedAndRounded() {
        assertTrue(WeatherClient.geocodeUrl("New Delhi").contains("name=New+Delhi"))
        assertTrue(WeatherClient.geocodeUrl("a&b=c").contains("a%26b%3Dc"))
        val u = WeatherClient.forecastUrl(WeatherClient.Place("M", "I", 19.07283, 72.88261))
        assertTrue(u, u.contains("latitude=19.0728&longitude=72.8826"))
    }

    @Test fun weatherCodesHaveWords() {
        assertTrue(WeatherClient.describe(95).contains("toofan"))
        assertTrue(WeatherClient.describe(63).contains("barish"))
        assertEquals("mausam badla hua hai", WeatherClient.describe(1234))
    }

    private fun fake(url: String): String = if (url.contains("geocoding-api")) geo else fc

    @Test fun lookupWorksWithCannedAnswers() {
        val a = WeatherClient.lookup("Mumbai") { u -> fake(u) }
        assertTrue(a is WeatherClient.Answer.Ok)
        assertTrue((a as WeatherClient.Answer.Ok).reply.startsWith("Mumbai mein abhi 31"))
    }

    @Test fun unknownCityIsNotFound() {
        val a = WeatherClient.lookup("Atlantis") { """{"results":[]}""" }
        assertTrue(a is WeatherClient.Answer.NotFound)
        assertEquals("Atlantis", (a as WeatherClient.Answer.NotFound).city)
    }

    @Test fun noInternetIsSaidPlainly() {
        val a = WeatherClient.lookup("Mumbai") { throw UnknownHostException("x") }
        assertTrue(a is WeatherClient.Answer.Failed)
        assertTrue((a as WeatherClient.Answer.Failed).reason.contains("Internet"))
    }

    @Test fun aForecastWeCannotReadIsAFailureNotAGuess() {
        val a = WeatherClient.lookup("Mumbai") { url -> if (url.contains("geocoding-api")) geo else "{}" }
        assertTrue(a is WeatherClient.Answer.Failed)
    }
}
