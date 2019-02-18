package com.swathub.robot

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Log4j
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.utils.IOUtils
import sun.misc.BASE64Decoder

import javax.servlet.MultipartConfigElement
import javax.servlet.http.Part
import java.security.MessageDigest
import java.util.zip.ZipFile

import static spark.Spark.*

@Log4j
class Manager {
	static nodeMap = [:]

	static void main(String [] args) {
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "OFF")

		log.info "Starting Manager Server..."

		def startServer = true
		port(23456)
		initExceptionHandler({ e ->
			log.error("Start Manager Server failed. (${e.message})")
			stop()
			startServer = false
		})
		staticFiles.externalLocation("data")
		init()
		awaitInitialization()

		if (startServer) {
			path("/robot", {
				def nodeName = ""

				before("/*", { req, res ->
					def auth = req.headers("Authorization")
					def credentials = auth ? new String(new BASE64Decoder().decodeBuffer(auth - "Basic ")).split(":") : null
					if (credentials && credentials.length == 2) {
						nodeName = credentials[0]
					} else {
						res.header("WWW-Authenticate", "Basic realm=\"SWATHub\"")
						halt(401, "Authorization required")
					}
				})

				get("/info", { req, res ->
					res.type("application/json")

					def subscription = [domain: "http://localhost:23456", type:"rpa"]
					def retObj = [subscription:subscription]
					return new JsonBuilder(retObj).toString()
				})

				post("/register", { req, res ->
					res.type("application/json")

					def body = new JsonSlurper().parseText(req.body())
					def nodeID = body.nodeID
					if (nodeID && nodeMap[nodeID]) {
						res.status(404)
						return new JsonBuilder([code:"error.exec.node.duplicate"]).toString()
					}

					nodeID = nodeID?:generateKey(32)
					nodeMap[nodeID] = [name:nodeName, spec:body.spec, tasks:[]]

					def retObj = [nodeID:nodeID, nodeName:nodeName]
					return new JsonBuilder(retObj).toString()
				})

				post("/:nodeID/unregister", { req, res ->
					res.type("application/json")

					nodeMap.remove(req.params(":nodeID"))

					def retObj = [:]
					return new JsonBuilder(retObj).toString()
				})

				post("/:nodeID/heartbeat", { req, res ->
					Thread.currentThread().setName("heartbeat")
					res.type("application/json")

					def node = nodeMap[req.params(":nodeID")]
					if (!node) {
						res.status(404)
						return new JsonBuilder([code:"error.node.notExists"]).toString()
					}

					return new JsonBuilder([:]).toString()
				})

				get("/:nodeID/tasks", { req, res ->
					Thread.currentThread().setName("getTasks")
					res.type("application/json")

					def node = nodeMap[req.params(":nodeID")]
					if (!node) {
						log.error("Robot node not exists!")
						res.status(404)
						return new JsonBuilder([code:"error.node.notExists"]).toString()
					}

					def retObj = [tasks:new JsonSlurper().parseText(new JsonBuilder(node.tasks).toString())]
					node.tasks = []
					return new JsonBuilder(retObj).toString()
				})

				post("/tasks/:taskCode/report", { req, res ->
					Thread.currentThread().setName("taskReport")
					res.type("application/json")

					log.debug(req.body())

					return new JsonBuilder([:]).toString()
				})

				post("/tasks/:taskCode/upload", { req, res ->
					Thread.currentThread().setName("taskUpload")
					res.type("application/json")

					def taskCode = req.params(":taskCode")

					req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"))
					try {
						def uploadFileIn = req.raw().getPart("files").getInputStream()

						def evidenceDir = new File("data/tasks/${taskCode}/evidence")
						evidenceDir.mkdir()
						def zipFileIn = new ZipArchiveInputStream(uploadFileIn)
						def zipEntry
						while (zipEntry = zipFileIn.getNextZipEntry()) {
							if (zipEntry.directory) {
								def zipDir = new File(evidenceDir, zipEntry.name)
								zipDir.mkdirs()
							} else {
								def zipFile = new File(evidenceDir, zipEntry.name)
								zipFile.bytes = IOUtils.toByteArray(zipFileIn)
							}
						}
						zipFileIn.close()
					} catch (Exception ignore) {
						res.status(404)
					}

					return new JsonBuilder([:]).toString()
				})

				post("/upload", { req, res ->
					Thread.currentThread().setName("upload")
					res.type("application/json")

					def uploadDir = new File("upload", nodeName)
					uploadDir.mkdir()

					req.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"))
					try {
						def uploadFileIn = req.raw().getPart("files").getInputStream()
						def fileName = getFileName(req.raw().getPart("files"))
						def uploadFile = new File(uploadDir, fileName)
						uploadFile.bytes = uploadFileIn.bytes
					} catch (Exception ignore) {
						res.status(404)
					}

					return new JsonBuilder([:]).toString()
				})
			})

			get("/nodes", { req, res ->
				res.type("application/json")

				def retObj = [nodes:nodeMap]
				return new JsonBuilder(retObj).toString()
			})

			post("/api/:method", { req, res ->
				def method = req.params(":method")
				Thread.currentThread().setName(method)
				res.type("application/json")
				def body = new JsonSlurper().parseText(req.body())

				def success
				def node = nodeMap.find { nodeID, node ->
					node.name == body.nodeName
				}
				if (node) {
					success = createTask(node.value, method, body)
				} else {
					log.error("Robot node not exists!")
					success = false
				}

				if (!success) {
					res.status(404)
				}
				return new JsonBuilder([:]).toString()
			})

			get("/temp", { req, res ->
				return "<form method='post' enctype='multipart/form-data' action='/robot/upload?nodeName=test'><input type='file' name='files' accept='.png'><button>Upload picture</button></form>"
			})

			notFound({ req, res ->
				res.type("application/json")
				return "{\"code\":\"Not found\", \"message\":\"App not found\"}"
			})

			internalServerError({ req, res ->
				res.type("application/json")
				return "{\"code\":\"Not found\", \"message\":\"System Error\"}"
			})
		}

		log.info "Manager Server started."
	}

	static createTask(node, taskName, data) {
		def spkgFile = new File("spkg", taskName + ".spkg")
		def taskPackage = [:]
		try {
			def zipFile = new ZipFile(spkgFile)
			zipFile.entries().each { zipEntry ->
				if (zipEntry.name == "scenario.json") {
					taskPackage = new JsonSlurper().parseText(zipFile.getInputStream(zipEntry).text)
					return
				}
			}
		} catch (IOException ignore) {
			log.error("SPKG file not valid!")
			return false
		}

		def params = []
		def scenario = taskPackage.scenario
		def entryFlow = scenario.flows[scenario.code]
		def scenarioOptions = scenario.options
		if (scenarioOptions.beforeInterceptor) {
			def interceptor = scenario.flows[scenarioOptions.beforeInterceptor]
			if (interceptor) {
				interceptor.params.each {
					if (it.direction != "output") params << it
				}
			}
		}
		entryFlow.params.each {
			if (it.direction != "output") params << it
		}
		if (scenarioOptions.afterInterceptor) {
			def interceptor = scenario.flows[scenarioOptions.afterInterceptor]
			if (interceptor) {
				interceptor.params.each {
					if (it.direction != "output") params << it
				}
			}
		}

		def testcase = [code:generateKey(32), name:new Date().getTime().toString(), revision:0, data:[]]
		params.each { param ->
			testcase.data << [code:param.code, name:param.code, value:data[param.code]?:"", enabled:data.containsKey(param.code)]
		}

		def task = [
				platformCode: node.spec.platforms[0].code,
				apiServer: "",
				testServer: ""
		]
		task.code = generateKey(32)

		taskPackage.testcase = testcase
		taskPackage.task = task

		def taskJsonString = new JsonBuilder(taskPackage).toString()

		def taskDir = new File("data/tasks/" + task.code)
		taskDir.mkdirs()
		def zipFile = new File(taskDir, "package.zip")
		def zipFileOut = new FileOutputStream(zipFile)
		def zipArchiveOut = new ZipArchiveOutputStream(zipFileOut)

		zipArchiveOut.putArchiveEntry(new ZipArchiveEntry("task.json"))
		zipArchiveOut.write(taskJsonString.bytes)
		zipArchiveOut.closeArchiveEntry()

		zipArchiveOut.close()
		zipFileOut.close()

		def checksum = new File(taskDir, "CHECKSUM")
		def fileSum = getMD5Checksum(zipFile)
		checksum.bytes = fileSum.bytes

		node.tasks << [
				code: task.code,
				platform: task.platformCode,
				sessions: 1,
				url: "http://localhost:23456/tasks/${task.code}/"
		]

		return true
	}

	static generateKey(int size) {
		def key = ""
		def rawKey = UUID.randomUUID().toString().replaceAll("-", "")

		if (size > 0 || size <= 32) {
			key = rawKey.substring(0, size)
		}

		return key
	}

	static getMD5Checksum(File file) {
		try {
			def fis =  new FileInputStream(file)

			def buffer = new byte[1024]
			def complete = MessageDigest.getInstance("MD5")
			def numRead = 0
			while (numRead != -1) {
				numRead = fis.read(buffer)
				if (numRead > 0) {
					complete.update(buffer, 0, numRead)
				}
			}
			fis.close()

			def bytes = complete.digest()
			def result = ""
			for (def i = 0; i < bytes.length; i++) {
				result += Integer.toString( ( bytes[i] & 0xff ) + 0x100, 16).substring( 1 )
			}
			return result
		}
		catch(Exception ignore) {
			return null
		}
	}

	static getFileName(Part part) {
		for (String cd : part.getHeader("content-disposition").split(";")) {
			if (cd.trim().startsWith("filename")) {
				return cd.substring(cd.indexOf('=') + 1).trim().replace("\"", "")
			}
		}
		return null
	}
}
