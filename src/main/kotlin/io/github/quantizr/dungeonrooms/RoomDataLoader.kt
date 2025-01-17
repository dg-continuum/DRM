package io.github.quantizr.dungeonrooms

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.quantizr.dungeonrooms.dungeons.data.meta.DungeonRoom
import io.github.quantizr.dungeonrooms.dungeons.data.meta.RoomMetaData
import io.github.quantizr.dungeonrooms.dungeons.data.meta.SecretMetaData
import io.github.quantizr.dungeonrooms.utils.Utils
import java.io.InputStreamReader
import java.util.HashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class RoomDataLoader {

    companion object {
        const val roomDataPath = "/assets/dungeonrooms/dungeonrooms.json"
        const val secretLocPath = "/assets/dungeonrooms/secretlocations.json"
    }

    private var executor: ExecutorService = Executors.newFixedThreadPool(4)

    // debug info
    private var blockedTime: Long = 0
    private var blockedTimeFor1x1: Long = 0
    private var asyncLoadStart: Long = 0
    private var asyncLoadTime: Long = 0

    // the actual data exposed to the world
    var ROOM_DATA = HashMap<String, HashMap<String, LongArray>>()
    lateinit var roomData: Map<String, DungeonRoom>

    // temporary vars used while loading the data
    private lateinit var future1x1: Future<HashMap<String, LongArray>>
    private lateinit var future1x2: Future<HashMap<String, LongArray>>
    private lateinit var future1x3: Future<HashMap<String, LongArray>>
    private lateinit var future1x4: Future<HashMap<String, LongArray>>
    private lateinit var future2x2: Future<HashMap<String, LongArray>>
    private lateinit var futureLShape: Future<HashMap<String, LongArray>>
    private lateinit var futurePuzzle: Future<HashMap<String, LongArray>>
    private lateinit var futureTrap: Future<HashMap<String, LongArray>>

    private lateinit var roomsJson: JsonObject
    private lateinit var waypointsJson: JsonObject

    fun startAsyncLoad(){
        asyncLoadStart = System.currentTimeMillis()

        // load the room skeletons
        val paths = Utils.getAllPaths("catacombs")
        future1x1 = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("1x1", paths) }
        future1x2 = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("1x2", paths) }
        future1x3 = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("1x3", paths) }
        future1x4 = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("1x4", paths) }
        future2x2 = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("2x2", paths) }
        futureLShape = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("L-shape", paths) }
        futurePuzzle = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("Puzzle", paths) }
        futureTrap = executor.submit<HashMap<String, LongArray>> { Utils.pathsToRoomData("Trap", paths) }

        // get room and waypoint info
        executor.submit {
            val gson = Gson()
            this::class.java.getResourceAsStream(roomDataPath)
                .use { fis ->
                    fis?.let {
                        InputStreamReader(fis).use { yas ->
                            roomsJson = gson.fromJson(yas, JsonObject::class.java)
                            DungeonRooms.logger.info("DungeonRooms: Loaded dungeonrooms.json")
                        }
                    }
                }
            this::class.java.getResourceAsStream(secretLocPath)
                .use { fis ->
                    fis?.let {
                        InputStreamReader(fis).use { yas ->
                            waypointsJson = gson.fromJson(yas, JsonObject::class.java)
                            DungeonRooms.logger.info("DungeonRooms: Loaded secretlocations.json")
                        }
                    }
                }

            constructRooms()
        }
    }

    fun blockTillLoad() {
        // set RoomData to futures - this will block if the rest of init was fast
        try {
            val oneByOneStart = System.currentTimeMillis()
            ROOM_DATA["1x1"] = future1x1.get()

            val restOfRoomsStart = System.currentTimeMillis()
            ROOM_DATA["1x2"] = future1x2.get()
            ROOM_DATA["1x3"] = future1x3.get()
            ROOM_DATA["1x4"] = future1x4.get()
            ROOM_DATA["2x2"] = future2x2.get()
            ROOM_DATA["L-shape"] = futureLShape.get()
            ROOM_DATA["Puzzle"] = futurePuzzle.get()
            ROOM_DATA["Trap"] = futureTrap.get()

            this.asyncLoadTime = oneByOneStart - asyncLoadStart
            this.blockedTimeFor1x1 = restOfRoomsStart - oneByOneStart
            this.blockedTime = System.currentTimeMillis() - restOfRoomsStart

        } catch (e: ExecutionException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        DungeonRooms.logger.debug("DungeonRooms: Time(ms) for init before get futures: $asyncLoadTime")
        DungeonRooms.logger.debug("DungeonRooms: Blocked Time(ms) for 1x1: $blockedTimeFor1x1")
        DungeonRooms.logger.debug("DungeonRooms: Blocked Time(ms) remaining for other rooms: $blockedTime")

        executor.shutdown()
    }


    private fun deconstructRoomData(): Map<String, RoomMetaData> {

        val tmpMap = HashMap<String, RoomMetaData>()

        roomsJson.entrySet().forEach {(name, element) ->
            if(element is JsonObject && element.has("category")) {
                val roomData = RoomMetaData(element.get("category").asString, element.get("secrets").asInt, element.get("fairysoul").asBoolean, element.get("dsg").asString, if(element.has("sbp")) element.get("sbp").asString else "null")
                tmpMap[name] = roomData
            }
        }

        return tmpMap
    }

    private fun deconstructSecretLocationMetadata(): Map<String, Array<SecretMetaData>> {

        val tmp = HashMap<String, Array<SecretMetaData>>()

        waypointsJson.entrySet().forEach { (name, element) ->
            if(element is JsonArray) {

                val secrets = element.map { it.asJsonObject }.map{ SecretMetaData(it.get("secretName").asString, it.get("category").asString, it.get("x").asInt, it.get("y").asInt, it.get("z").asInt) }.toTypedArray()

                tmp[name] = secrets
            }
        }

        return tmp
    }


    private fun constructRooms(){
        val roomMetaData = deconstructRoomData()
        val secretMetaData = deconstructSecretLocationMetadata()

        val tmp = HashMap<String, DungeonRoom>()

        secretMetaData.forEach { (secretName, secrets) ->
            roomMetaData.forEach { (metaName, meta) ->
                if(secretName == metaName) {
                    tmp[secretName] = DungeonRoom(secretName, meta, secrets)
                }
            }
        }
        println("Example room data: ${tmp["Perch-2"]}")
        roomData = tmp
    }

}