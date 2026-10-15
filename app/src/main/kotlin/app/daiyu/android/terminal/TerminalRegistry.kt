package app.daiyu.android.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Blocking entrypoints: invoke off the main thread. Process handles survive registry close,
 * not app restart. This adapter does not replace the reviewed LongShell/Linux path mapper. */
class TerminalRegistry(context: Context) : AutoCloseable {
    private val context=context.applicationContext
    private var configuration=TerminalConfiguration()
    private var scope:String?=null
    private var conversationOwner:String?=null
    private var journal:OperationJournal?=null
    private var selected:SelectedBackend?=null
    private var selectionError:String?=null
    private var selectedPaths: List<BackendPathIdentity>? = null
    @Volatile private var closed=false
    private val backendLock=Any()
    private val sessions=mutableSetOf<String>()
    private val directory=File(this.context.filesDir,"terminal-jobs").apply { TerminalDurability.ensureDirectory(this) }
    // Fault seams for deterministic tests; not schema/model configurable.
    internal var backendOverride:SelectedBackend?=null
    internal var startProcess:(ProcessBuilder)->Process={ it.start() }
    internal var beforeJobSave:(()->Unit)?=null
    internal var processStartReader: (Long) -> String? = { processStart(it) }
    @Synchronized fun configure(sessionId:String, configuration:TerminalConfiguration, conversationOwnerId:String=sessionId) {
        check(scope==null) { "Registry configuration is immutable; create a new registry for a new run" }
        require(conversationOwnerId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val frozen=configuration.copy(environment=configuration.environment.toMap(),linux=configuration.linux?.let {
            it.copy(shellArgv=it.shellArgv.toList(),identityArgv=it.identityArgv.toList(),environment=it.environment.toMap(),sharedMounts=it.sharedMounts.toMap()) })
        val boundJournal = OperationJournal(File(context.filesDir,"terminal-journal"),sessionId,
            binding = JSONObject().put("owner", conversationOwnerId).put("run", sessionId)
                .put("storage", directory.canonicalPath).put("configuration", configurationBinding(frozen)))
        val bindingResult = boundJournal.verifyBinding()
        check(bindingResult.optBoolean("ok")) { bindingResult.optString("error", "operation_binding_unavailable") }
        conversationOwner=conversationOwnerId
        journal=boundJournal
        this.configuration=frozen
        scope=sessionId
    }
    private fun configurationBinding(value: TerminalConfiguration): JSONObject = JSONObject()
        .put("backend", value.backend).put("root", value.root)
        .put("cwd", value.workingDirectory?.absolutePath ?: JSONObject.NULL)
        .put("environment", JSONObject(value.environment)).put("root_executable", value.rootExecutable)
        .put("linux", value.linux?.let { linux ->
            JSONObject().put("label", linux.label).put("environment_id", linux.environmentId)
                .put("argv", JSONArray(linux.shellArgv)).put("identity_argv", JSONArray(linux.identityArgv))
                .put("environment", JSONObject(linux.environment)).put("uid", linux.expectedUid)
                .put("daemon", linux.daemonSupported).put("cwd", linux.guestWorkingDirectory)
                .put("mounts", JSONObject(linux.sharedMounts))
        } ?: JSONObject.NULL)

    val schemas:JSONArray get()=JSONArray().apply {
        put(schema("terminal_open","打开固定后端会话；验证身份失败不会执行命令；失效会话重放返回expired",true,fields("request_id" to "string"),listOf("request_id")))
        put(schema("terminal_exec","固定环境命令；sync限期等待；async/daemon已派发但结果unknown，凭job_id查询；不重发",true,
            fields("request_id" to "string","session_id" to "string","command" to "string","mode" to "string","timeout_ms" to "integer"),listOf("request_id","session_id","command")))
        put(schema("terminal_poll","读取有界实际日志及任务状态；不执行/重放命令",false,fields("job_id" to "string","offset" to "integer","limit" to "integer"),listOf("job_id")))
        put(schema("terminal_list","列本conversation daemon及本run普通任务；仅同app进程保留安全句柄",false,fields(),emptyList()))
        put(schema("terminal_stop","核实进程所有者后停止；丢失句柄拒绝按裸PID停止",true,fields("request_id" to "string","job_id" to "string"),listOf("request_id","job_id")))
        put(schema("terminal_close","关闭会话及普通任务；daemon仅同app进程独立于registry",true,fields("request_id" to "string","session_id" to "string"),listOf("request_id","session_id")))
    }
    fun execute(name:String,arguments:JSONObject):JSONObject {
        if(name !in names) return error("unknown_tool")
        if(scope==null) return error("run_session_not_configured")
        if (closed) return error("run_session_closed")
        try {
            val binding = journal!!.verifyBinding()
            if (!binding.optBoolean("ok")) return binding
            val defs=schemas
            val definition=(0 until defs.length()).map { defs.getJSONObject(it) }.first { it.getString("name")==name }.getJSONObject("parameters")
            val props=definition.getJSONObject("properties"); val required=definition.getJSONArray("required")
            for(i in 0 until required.length()) require(arguments.has(required.getString(i)))
            arguments.keys().forEach { key ->
                require(props.has(key)); val value=arguments.get(key)
                require(if(props.getJSONObject(key).getString("type")=="integer") value is Number && value.toDouble()==value.toLong().toDouble() else value is String)
            }
            if(name=="terminal_poll") return poll(arguments)
            if(name=="terminal_list") return listJobs()
            val effective = if (name == "terminal_exec") TerminalArguments.execution(arguments) else JSONObject(arguments.toString())
            val identity = if (name == "terminal_exec") TerminalArguments.identity(effective) else effective
            val result=journal!!.once(effective.getString("request_id"),name,identity) {
                try { when(name) {
                    "terminal_open" -> open()
                    "terminal_exec" -> exec(effective)
                    "terminal_stop" -> stop(arguments.getString("job_id"))
                    "terminal_close" -> closeSession(arguments.getString("session_id"))
                    else -> error("unknown_tool")
                } } catch(e:BackendUnavailable) { error(e.code).apply {
                    if(e.code.startsWith("linux_")) put("installation_entry",configuration.linux?.installationEntry ?: "settings://terminal/linux")
                    if(e.code.startsWith("root_")) put("required_identity","root; user command not executed")
                } } catch(_:IllegalArgumentException) { error("invalid_arguments") }
            }
            if(name=="terminal_open" && result.optBoolean("ok") && !synchronized(sessions) { result.optString("session_id") in sessions })
                return error("session_expired_not_reopened").put("state","expired").put("session_id",result.optString("session_id"))
            return result
        } catch(_:IllegalArgumentException) { return error("invalid_arguments") }
        catch(_:org.json.JSONException) { return error("invalid_arguments") }
        catch(_:Exception) { return error("operation_journal_unavailable").put("execution","unknown") }
    }
    private fun backend():SelectedBackend {
        return synchronized(backendLock) {
            backendOverride?.let { return@synchronized it }
            selectionError?.let { throw BackendUnavailable(it) }
            try {
                selectedPaths?.let { if (BackendSelection.paths(context, configuration) != it) throw BackendUnavailable("backend_path_changed_not_executed") }
                if (configuration.backend == "linux" && configuration.linux == null) throw BackendUnavailable("linux_backend_not_configured")
                if (configuration.backend == "android" && configuration.root &&
                    (!File(configuration.rootExecutable).isAbsolute || !File(configuration.rootExecutable).canExecute())) throw BackendUnavailable("root_unavailable")
                val before = BackendSelection.paths(context, configuration)
                val fresh = BackendSelection.select(context, configuration)
                val paths = BackendSelection.paths(context, configuration)
                if (before != paths) throw BackendUnavailable("backend_path_changed_not_executed")
                selected?.let { if (fresh != it || paths != selectedPaths) throw BackendUnavailable("backend_identity_changed_not_executed") }
                if (selected == null) { selected = fresh; selectedPaths = paths }
                fresh
            } catch(e:BackendUnavailable) { selectionError=e.code; throw e }
        }
    }
    private fun open():JSONObject {
        val b=backend(); val id=UUID.randomUUID().toString()
        synchronized(sessions) { if (closed) return error("run_session_closed"); if(sessions.size>=MAX_JOBS) return error("session_limit"); sessions.add(id) }
        return identity(b).put("session_id",id).put("session_semantics","fixed_environment_command_shell; no persistent shell variables or cwd")
    }
    private fun exec(args:JSONObject):JSONObject {
        val session=args.getString("session_id")
        if(!synchronized(sessions) { session in sessions }) return error("session_closed_or_unknown")
        val command=args.getString("command"); require(command.isNotBlank() && command.length<=32768 && !command.contains('\u0000'))
        val mode=args.optString("mode","sync"); require(mode in setOf("sync","async","daemon"))
        val timeout=args.optLong("timeout_ms",30000); require(timeout in 1..120000)
        val b=backend()
        val argv=if(mode=="daemon") {
            if(!b.daemonSupported) return error("daemon_not_supported_by_backend")
            val executable=listOf("/system/bin/setsid","/system/xbin/setsid").firstOrNull { File(it).canExecute() }
                ?: return error("daemon_detach_unavailable")
            if(b.name!="android" || b.root) return error("daemon_detach_not_supported_for_selected_identity")
            listOf(executable,"-w","--")+b.shell
        } else b.shell
        val id=UUID.randomUUID().toString()
        val job=Job(id,scope!!,conversationOwner!!,session,mode,b,File(directory,"$id.log"),File(directory,"$id.script"))
        try {
            synchronized(jobs) {
                if(jobs.size>=MAX_GLOBAL_JOBS || jobs.values.count { it.log.parentFile.canonicalPath==directory.canonicalPath }>=MAX_JOBS || !storageAvailable()) return error("job_or_log_retention_limit")
                // Durable pending metadata must precede process launch and command dispatch.
                jobs[id]=job // Even reservation persistence failure remains inspectable in this process.
                saveJob(job)
            }
            if (closed || !synchronized(sessions) { session in sessions }) job.cancelRequested=true
            val prefix=b.guestCwd?.let { "cd -- '"+it.replace("'","'\\''")+"' || exit 125\n" }.orEmpty()
            val bytes=(prefix+command+"\n").toByteArray(Charsets.UTF_8)
            require(bytes.size<=MAX_SCRIPT_BYTES)
            java.io.FileOutputStream(job.script).use { it.write(bytes); it.fd.sync() }
            android.system.Os.chmod(job.script.path,384) // 0600, private bounded stdin, no blocking pipe write.
            job.log.createNewFile()
            synchronized(sessions) {
                synchronized(job) {
                    if (closed || session !in sessions || job.cancelRequested) {
                        job.state = "cancelled_before_dispatch"
                        return error("session_closed_before_dispatch").put("job_id", id)
                    }
                    // A short claim is the dispatch linearization point; no process I/O under this lock.
                    job.state = "dispatching"
                }
            }
            if (backendOverride == null && BackendSelection.paths(context, configuration) != selectedPaths) {
                job.state="start_failed"
                return error("backend_path_changed_not_executed").put("job_id", id)
            }
            val startedAt=System.nanoTime()
            val process=try { startProcess(b.builder(argv).redirectErrorStream(true).redirectInput(job.script)) }
            catch(_:Exception) { job.state="start_failed"; saveJob(job); return error("process_start_failed").put("job_id",id) }
            val pid=try { process.pid() } catch(_:LinkageError) { -1L } catch(_:UnsupportedOperationException) { -1L }
            val start=try { processStartReader(pid) } catch(_:Exception) { null }
            synchronized(job) {
                job.handle=OwnedProcess(process, pid, start); job.state="dispatched"
            }
            applyCancellation(job)
            startLogPump(job,process)
            try { saveJob(job) } catch(_:Exception) { job.unknown=true; return dispatchedError(job,"job_metadata_commit_unknown_not_replayed") }
            if(job.cancelRequested) return dispatchedError(job,"session_closed_during_dispatch_unknown")
            if(mode!="sync") return identity(b).put("execution","unknown").put("job_id",id).put("state","dispatched")
                .put("lifetime",if(mode=="daemon") "independent_of_registry_only_while_same_app_process_lives" else "session")
            val remaining=timeout-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startedAt)
            val completed=try { remaining>0 && process.waitFor(remaining,TimeUnit.MILLISECONDS) }
                catch(_:InterruptedException) { Thread.currentThread().interrupt(); false }
            if(!completed) job.unknown=true
            if(completed) job.reader?.join(200)
            try { saveJob(job) } catch(_:Exception) { job.unknown=true; return dispatchedError(job,"job_metadata_commit_unknown_not_replayed") }
            return page(job,0,16384).put("ok",completed).put("execution",if(completed && !job.unknown && job.outputComplete) "executed" else "unknown")
                .apply { if(!completed) put("error","timeout_outcome_unknown_not_replayed") }
        } catch(_:Exception) {
            if(job.handle!=null) { job.unknown=true; return dispatchedError(job,"command_delivery_unknown_not_replayed") }
            return error("job_preparation_failed_not_dispatched").put("job_id",id)
        } finally {
            applyCancellation(job)
            if (job.handle == null) job.script.delete()
        }
    }
    private fun dispatchedError(job:Job,code:String)=identity(job.backend).put("ok",false).put("error",code).put("execution","unknown").put("job_id",job.id).put("state","dispatched")
    private fun startLogPump(job:Job,process:Process) {
        job.reader=Thread {
            try {
                process.inputStream.use { input -> java.io.FileOutputStream(job.log).use { output ->
                    val bytes=ByteArray(8192); var retained=0; var n=input.read(bytes)
                    while(n>=0) {
                        val keep=minOf(n,MAX_LOG_BYTES-retained)
                        if(keep>0) { output.write(bytes,0,keep); output.flush(); retained+=keep }
                        if(keep<n) job.truncated=true
                        n=input.read(bytes) // drain/discard beyond quota, never accumulate unbounded output
                    }
                } }
            } catch(_:Exception) { job.unknown=true }
            finally { job.script.delete(); job.outputComplete=true }
        }.apply { isDaemon=true; name="terminal-log-${job.id}"; start() }
    }
    private fun poll(args:JSONObject):JSONObject {
        val id=args.getString("job_id"); require(id.matches(Regex("[a-f0-9-]{36}")))
        val offset=args.optLong("offset",0); val limit=args.optInt("limit",16384); require(offset>=0 && limit in 1..65536)
        val job=synchronized(jobs) { jobs[id] }
        if(job!=null) { if(!owns(job)) return error("job_not_owned_by_conversation_or_run"); return page(job,offset,limit) }
        val meta=File(directory,"$id.json")
        if(!meta.isFile) return error("job_not_found")
        val record=readRecord(meta); if(!owns(record)) return error("job_not_owned_by_conversation_or_run")
        return readLog(File(directory,"$id.log"),offset,limit).put("job_id",id).put("backend",record.getString("backend"))
            .put("state","unknown_after_process_restart").put("exit_code",JSONObject.NULL).put("execution","unknown")
    }
    private fun page(job:Job,offset:Long,limit:Int):JSONObject {
        val process=job.handle?.process; val running=process?.isAlive ?: false
        return readLog(job.log,offset,limit).put("job_id",job.id).put("backend",job.backend.name).put("uid",job.backend.uid).put("root",job.backend.root)
            .put("state",if(process==null) job.state else if(running) "running" else "exited")
            .put("exit_code",if(process==null || running) JSONObject.NULL else process.exitValue())
            .put("execution",if(process==null) (if(job.state in setOf("start_failed", "cancelled_before_dispatch")) "not_executed" else "unknown") else if(running || job.unknown || !job.outputComplete) "unknown" else "executed")
            .put("stderr_merged",true).put("log_truncated",job.truncated).put("output_complete",job.outputComplete)
    }
    private fun readLog(file:File,offset:Long,limit:Int):JSONObject {
        if(!file.exists()) return JSONObject().put("ok",true).put("stdout","").put("stdout_base64","").put("total_bytes",0).put("log_available",false)
        return RandomAccessFile(file,"r").use { input ->
            val size=minOf(input.length(),MAX_LOG_BYTES.toLong()); require(offset<=size); input.seek(offset)
            val bytes=ByteArray(minOf(limit.toLong(),size-offset).toInt()); input.readFully(bytes)
            JSONObject().put("ok",true).put("stdout",String(bytes,Charsets.UTF_8)).put("stdout_base64",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
                .put("offset",offset).put("next_offset",offset+bytes.size).put("has_more",offset+bytes.size<size).put("total_bytes",size)
        }
    }
    private fun stop(id:String):JSONObject {
        val job=synchronized(jobs) { jobs[id] } ?: return error("owner_process_handle_unavailable_not_stopped")
        if(!owns(job)) return error("job_not_owned_by_conversation_or_run")
        val handle=synchronized(job) {
            job.cancelRequested = true
            job.handle
        } ?: return JSONObject().put("ok", true).put("execution", if(job.state=="dispatching") "unknown" else "not_executed")
            .put("job_id",id).put("state",job.state).put("stop_requested",true).put("stopped",false)
        if (!signalOwned(job, handle)) return error("process_owner_unverified_not_stopped").put("execution", "unknown").put("job_id", id)
        val exited=handle.process.waitFor(1500,TimeUnit.MILLISECONDS)
        return page(job,0,16384).put("stopped",exited).put("stop_scope","owned_shell_only; descendants not claimed stopped")
    }
    private fun signalOwned(job: Job, handle: OwnedProcess): Boolean {
        if (!handle.process.isAlive) return true
        if (handle.start==null || processStartReader(handle.pid)!=handle.start) { job.unknown=true; return false }
        handle.process.destroy()
        return true
    }
    private fun applyCancellation(job: Job) {
        val handle=synchronized(job) { if (job.cancelRequested) job.handle else null } ?: return
        job.unknown=true
        signalOwned(job, handle)
    }

    private fun closeSession(id:String):JSONObject {
        val owned=synchronized(sessions) {
            if(!sessions.remove(id)) return error("session_closed_or_unknown")
            synchronized(jobs) {
                jobs.values.filter { owns(it) && it.scope==scope && it.session==id && it.mode!="daemon" }.onEach {
                    synchronized(it) { it.cancelRequested=true }
                }.map { it.id }
            }
        }
        val results=owned.map { stop(it) }
        return JSONObject().put("ok",true).put("session_id",id).put("jobs",JSONArray(results))
            .put("cancellation_complete", results.all { it.optBoolean("stopped") || it.optString("state")=="exited" })
            .put("daemons_preserved",true)
    }
    override fun close() {
        synchronized(sessions) {
            if (closed) return
            closed = true
            sessions.clear()
        }
        val ordinary=synchronized(jobs) { jobs.values.filter { owns(it) && it.scope==scope && it.mode!="daemon" } }
        ordinary.forEach { synchronized(it) { it.cancelRequested=true } }
        ordinary.forEach { applyCancellation(it) }
    }
    /** Explicit trusted-host cleanup after run closure; unresolved journals remain intact. */
    fun retireCompletedScope(): JSONObject {
        if (!closed) return error("run_must_close_before_retirement")
        return journal?.retireScope() ?: error("run_session_not_configured")
    }
    private fun owns(job:Job)=job.log.parentFile.canonicalPath==directory.canonicalPath && job.owner==conversationOwner && (job.mode=="daemon" || job.scope==scope)
    private fun owns(record:JSONObject)=record.optString("owner")==conversationOwner && (record.optString("mode")=="daemon" || record.optString("scope")==scope)
    private fun listJobs():JSONObject {
        val records=linkedMapOf<String,JSONObject>()
        var scanned=0
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for(entry in stream) {
                if(++scanned>MAX_FILES) return error("job_directory_scan_limit")
                val file=entry.toFile()
                if(file.extension!="json") continue
                try { val record=readRecord(file); if(owns(record)) records[file.nameWithoutExtension]=record.put("job_id",file.nameWithoutExtension).put("state","unknown_without_live_handle").put("execution","unknown") }
                catch(_:Exception) { }
            }
        }
        synchronized(jobs) { jobs.values.filter { owns(it) }.forEach { job -> records[job.id]=page(job,0,1) } }
        return JSONObject().put("ok",true).put("jobs",JSONArray(records.values.toList())).put("limit",MAX_JOBS)
    }
    private fun storageAvailable():Boolean {
        // Called with jobs locked: reserve the full output allowance before dispatch, including
        // jobs whose pending metadata save failed. Delayed pumps must not share unused budget.
        val reserved=jobs.values.filter { it.log.parentFile==directory }.mapTo(mutableSetOf()) { it.id }
        val logs=mutableMapOf<String,Long>(); var scanned=0
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for(entry in stream) {
                if(++scanned>MAX_FILES) return false
                val file=entry.toFile()
                if(file.extension=="json") reserved.add(file.nameWithoutExtension)
                if(file.extension=="log") {
                    val length=file.length(); if(length>MAX_LOG_BYTES) return false
                    logs[file.nameWithoutExtension]=length
                }
            }
        }
        // Unknown/orphan logs are retained, not deleted. Metadata (even unreadable metadata)
        // reserves a whole log; orphan logs consume their actual bytes in addition to that.
        val orphanBytes=logs.filterKeys { it !in reserved }.values.sum()
        return reserved.size<MAX_JOBS && orphanBytes+(reserved.size+1L)*MAX_LOG_BYTES<=MAX_TOTAL_LOG_BYTES && scanned+4<=MAX_FILES
    }
    private fun readRecord(file:File):JSONObject { check(file.length()<=16384); return JSONObject(file.readText()) }
    private fun saveJob(job:Job) {
        beforeJobSave?.invoke()
        val file=File(directory,"${job.id}.json")
        val handle=job.handle
        val value=identity(job.backend).put("scope",job.scope).put("owner",job.owner).put("session_id",job.session).put("mode",job.mode)
            .put("pid",handle?.pid ?: -1L).put("process_start",handle?.start).put("state",job.state).put("job_id",job.id)
            .put("execution",if(job.state in setOf("start_failed", "cancelled_before_dispatch")) "not_executed" else "unknown")
        val bytes=value.toString().toByteArray(Charsets.UTF_8); check(bytes.size<=16384)
        TerminalDurability.replace(file, bytes)
    }
    private fun identity(b:SelectedBackend)=JSONObject().put("ok",true).put("backend",b.name).put("uid",b.uid).put("root",b.root).put("working_directory",b.guestCwd ?: b.cwd.absolutePath)
        .put("guest_uid", if(b.name.startsWith("linux:")) b.uid else JSONObject.NULL).put("host_root",b.root)
        .put("environment_id",b.environmentId).put("shared_mounts",JSONObject(b.sharedMounts)).put("execution","not_executed")
    companion object {
        val names=setOf("terminal_open","terminal_exec","terminal_poll","terminal_stop","terminal_close","terminal_list")
        private const val MAX_GLOBAL_JOBS=1024
        private const val MAX_JOBS=64
        private const val MAX_FILES=256
        private const val MAX_SCRIPT_BYTES=131072
        private const val MAX_LOG_BYTES=262144
        private const val MAX_TOTAL_LOG_BYTES=16L*1024*1024
        private val jobs=mutableMapOf<String,Job>() // bounded, fail closed; no unresolved ledger deletion
        private fun processStart(pid:Long):String?=try {
            val stat=File("/proc/$pid/stat").readText(); stat.substring(stat.lastIndexOf(')')+2).split(' ')[19]
        } catch(_:Exception) { null }
        private fun error(code:String)=OperationJournal.failure(code)
        private fun fields(vararg fields:Pair<String,String>)=JSONObject().apply { fields.forEach { (name,type) -> put(name,JSONObject().put("type",type)) } }
        private fun schema(name:String,description:String,effect:Boolean,fields:JSONObject,required:List<String>)=JSONObject().put("name",name).put("description",description).put("sideEffect",effect)
            .put("parameters",JSONObject().put("type","object").put("properties",fields).put("required",JSONArray(required)).put("additionalProperties",false))
    }
    private data class OwnedProcess(val process: Process, val pid: Long, val start: String?)
    private class Job(val id:String,val scope:String,val owner:String,val session:String,val mode:String,val backend:SelectedBackend,val log:File,val script:File) {
        @Volatile var cancelRequested=false
        @Volatile var handle:OwnedProcess?=null
        @Volatile var state="pending"
        @Volatile var unknown=false
        @Volatile var truncated=false
        @Volatile var outputComplete=false
        @Volatile var reader:Thread?=null
    }
}
