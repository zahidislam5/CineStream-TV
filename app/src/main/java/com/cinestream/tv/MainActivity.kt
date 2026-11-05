package com.cinestream.tv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Lightweight TV view; all library lookups and crawling happen outside the UI thread. */
class MainActivity:Activity(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val prefs by lazy{getSharedPreferences("cs_prefs",MODE_PRIVATE)}
    private val posterProxy by lazy{PosterProxy(this)}
    private lateinit var root:FrameLayout
    private lateinit var web:WebView
    private lateinit var store:LibraryStore
    private var initialized=false
    private var pageReady=false
    private var loadJob:Job?=null
    private var scanJob:Job?=null
    private var browseRequest=0
    private var player:ExoPlayer?=null
    private var playerContainer:FrameLayout?=null
    private var playingId:String?=null
    private var playingUrl:String?=null

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        try{
            root=FrameLayout(this)
            setContentView(root)
            val loader=WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
            web=WebView(this).apply{
                settings.javaScriptEnabled=true
                settings.domStorageEnabled=true
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.allowFileAccess=false
                settings.allowContentAccess=false
                settings.javaScriptCanOpenWindowsAutomatically=false
                webChromeClient=WebChromeClient()
                webViewClient=object:WebViewClientCompat(){
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?{
                        val target=request.url
                        if(target.scheme=="http"){
                            val path=target.toString()
                            if(Sources.defaults.any{CatalogScanner.allowed(path,it.url)}){
                                return posterProxy.fetch(path)?:emptyResponse()
                            }
                            return emptyResponse()
                        }
                        return loader.shouldInterceptRequest(target)
                    }
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                        return !(request.url.scheme=="https" &&
                            request.url.host=="appassets.androidplatform.net" &&
                            request.url.path?.startsWith("/assets/")==true)
                    }
                    override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{
                        Log.e("ZAHRUS-TV","WebView renderer terminated (crash="+detail.didCrash()+")")
                        showRecovery("TV WebView stopped unexpectedly. Try restarting the device.")
                        return true
                    }
                    override fun onPageFinished(view:WebView,url:String){
                        if(url.startsWith("https://appassets.androidplatform.net/assets/")){
                            pageReady=true
                            send("status","Loading saved library...")
                            startDatabase()
                        }
                    }
                }
                addJavascriptInterface(Bridge(),"NativeTV")
                loadUrl("https://appassets.androidplatform.net/assets/index.html")
                isFocusableInTouchMode=true
            }
            root.addView(web,FrameLayout.LayoutParams(-1,-1))
            web.requestFocus()
        }catch(e:Exception){
            Log.e("ZAHRUS-TV","Startup error",e)
            showRecovery("Could not open the TV app. Saved data was not deleted.")
        }
    }
    private fun emptyResponse():WebResourceResponse=
        WebResourceResponse("image/jpeg",null,ByteArrayInputStream(ByteArray(0)))

    private fun startDatabase(){
        if(initialized||loadJob?.isActive==true)return
        loadJob=scope.launch {
            try{
                send("status","Preparing your preloaded movie library (first launch may take longer)...")
                val seedMessage=withContext(Dispatchers.IO){
                    val fresh=PreloadedCatalog.installForFreshApp(this@MainActivity)
                    store=LibraryStore(this@MainActivity)
                    store.writableDatabase
                    val merged=if(!fresh)PreloadedCatalog.importForExistingApp(this@MainActivity,store) else false
                    store.importFavorites(prefs)
                    when {
                        fresh -> "Installed the preloaded catalog"
                        merged -> "Merged the preloaded catalog with your saved data"
                        PreloadedCatalog.available(this@MainActivity) -> "Preloaded catalog ready"
                        else -> "No bundled catalog in this build"
                    }
                }
                initialized=true
                sendCategories("Home")
                sendHome()
                val stats=withContext(Dispatchers.IO){store.scanStats()}
                send("status",seedMessage+" — "+stats.files+
                    " video files. Use Scan All only when you want to find server updates.")
                sendProgress(stats,"Library ready • "+stats.files+" preloaded video files")
            }catch(e:Exception){
                Log.e("ZAHRUS-TV","Preloaded catalog failed",e)
                showRecovery("Could not prepare preloaded library: "+
                    (e.message?:"Unknown error")+". Existing user data was not deleted.")
            }
        }
    }
    private fun send(type:String,data:String){
        if(!pageReady||!::web.isInitialized)return
        runOnUiThread{
            if(pageReady)try{
                web.evaluateJavascript("window.fromNative("+JSONObject.quote(type)+","+
                    JSONObject.quote(data)+")",null)
            }catch(e:Exception){Log.w("ZAHRUS-TV","UI update skipped",e)}
        }
    }
    private fun media(item:MediaEntry)=JSONObject().apply{
        put("id",item.id)
        put("title",item.title)
        put("kind",item.kind)
        put("category",item.category)
        put("year",item.year?:"")
        put("size",item.size)
        put("modified",item.modified)
        put("season",item.season?:JSONObject.NULL)
        put("episode",item.episode?:JSONObject.NULL)
        put("quality",item.quality?:"")
        put("genre",item.genre?:"")
        put("poster",item.poster?:"")
        put("favorite",prefs.getBoolean("fav_"+item.id,false))
        put("position",prefs.getLong("resume_"+item.id,0L))
    }
    private fun sendCategories(tab:String){
        if(!initialized)return
        scope.launch{
            try{
                val categories=withContext(Dispatchers.IO){store.categories(tab)}
                val years=withContext(Dispatchers.IO){store.years()}
                val info=JSONObject()
                val counts=JSONArray()
                categories.forEach{counts.put(JSONObject().put("name",it.name).put("count",it.count))}
                info.put("categories",counts)
                info.put("years",JSONArray(years))
                info.put("tab",tab)
                send("categories",info.toString())
            }catch(e:Exception){Log.e("ZAHRUS-TV","Categories failed",e)}
        }
    }
    private fun sendHome(){
        if(!initialized)return
        scope.launch {
            try{
                val groups=withContext(Dispatchers.IO){
                    val out=mutableListOf<Pair<String,List<MediaEntry>>>()
                    out.add("Recently Added" to store.browse("Latest","","","","new",0,9).results)
                    // Query each source category, but send at most eight compact groups.
                    val preferred=Sources.defaults.sortedBy{if(it.kind=="Series")0 else 1}
                    for(source in preferred){
                        val tab=if(source.kind=="Series")"Series" else "Movies"
                        val media=store.browse(tab,source.label,"","","new",0,7).results
                        if(media.isNotEmpty())out.add(source.label to media)
                        if(out.size>=9)break
                    }
                    out
                }
                val json=JSONArray()
                for(group in groups){
                    val array=JSONArray()
                    group.second.forEach{array.put(media(it))}
                    json.put(JSONObject().put("title",group.first).put("items",array))
                }
                send("home",json.toString())
            }catch(e:Exception){Log.e("ZAHRUS-TV","Home categories failed",e)}
        }
    }
    private fun browse(tab:String,category:String,year:String,query:String,sort:String,offset:Int,requestId:Int){
        if(!initialized){send("status","Library is opening. Please try again.");return}
        browseRequest=requestId
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){
                    store.browse(tab,category.take(120),year.take(4),query.take(120),
                        sort,offset.coerceAtLeast(0),48)
                }
                if(requestId!=browseRequest)return@launch
                val arr=JSONArray()
                result.results.forEach{arr.put(media(it))}
                val payload=JSONObject()
                payload.put("requestId",requestId)
                payload.put("total",result.total)
                payload.put("offset",offset.coerceAtLeast(0))
                payload.put("items",arr)
                send("browse",payload.toString())
            }catch(e:Exception){
                Log.e("ZAHRUS-TV","Search failed",e)
                send("status","Search is temporarily unavailable.")
            }
        }
    }
    private fun sendDetails(id:String,offset:Int){
        if(!initialized)return
        scope.launch{
            try{
                val result=withContext(Dispatchers.IO){
                    val item=store.get(id)?:return@withContext null
                    if(item.kind!="Series") return@withContext BrowseResult(0,emptyList())
                    store.episodes(item,offset)
                }?:return@launch
                val arr=JSONArray()
                result.results.forEach{arr.put(media(it))}
                send("details",JSONObject().put("id",id).put("offset",offset)
                    .put("total",result.total).put("episodes",arr).toString())
            }catch(e:Exception){Log.e("ZAHRUS-TV","Episode lookup failed",e)}
        }
    }
    private fun sendProgress(stats:ScanStats,message:String){
        send("progress",JSONObject().put("message",message)
            .put("indexed",stats.files).put("pending",stats.pending)
            .put("done",stats.done).put("failed",stats.failed)
            .put("scanning",scanJob?.isActive==true).toString())
    }

    inner class Bridge{
        @JavascriptInterface fun ready()=runOnUiThread{if(initialized){sendCategories("Home");sendHome()}}
        @JavascriptInterface fun home()=runOnUiThread{sendHome()}
        @JavascriptInterface fun categories(tab:String)=runOnUiThread{sendCategories(tab)}
        @JavascriptInterface fun browse(tab:String,category:String,year:String,query:String,sort:String,offset:Int,requestId:Int)=
            runOnUiThread{this@MainActivity.browse(tab,category,year,query,sort,offset,requestId)}
        @JavascriptInterface fun details(id:String,offset:Int)=runOnUiThread{sendDetails(id,offset)}
        @JavascriptInterface fun refresh()=runOnUiThread{startScan()}
        @JavascriptInterface fun stopScan()=runOnUiThread{scanJob?.cancel();send("status","Scan paused. Press Scan again to resume.")}
        @JavascriptInterface fun play(id:String)=runOnUiThread{launchPlay(id)}
        @JavascriptInterface fun favorite(id:String,value:Boolean)=runOnUiThread{
            if(!initialized)return@runOnUiThread
            scope.launch{
                try{
                    withContext(Dispatchers.IO){store.setFavorite(id,value)}
                    prefs.edit().putBoolean("fav_"+id,value).apply()
                    send("favorite",JSONObject().put("id",id).put("value",value).toString())
                }catch(e:Exception){Log.e("ZAHRUS-TV","Favorite update failed",e)}
            }
        }
        @JavascriptInterface fun external(id:String)=runOnUiThread{
            if(!initialized)return@runOnUiThread
            scope.launch{
                val item=withContext(Dispatchers.IO){store.get(id)}?:return@launch
                if(!allowedMedia(item.url))return@launch
                try{
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.url),"video/*"))
                }catch(e:Exception){Toast.makeText(this@MainActivity,"No external video player",Toast.LENGTH_LONG).show()}
            }
        }
    }
    private fun allowedMedia(url:String)=Sources.defaults.any{CatalogScanner.allowed(url,it.url)}

    private fun startScan(){
        if(!initialized){send("status","Saved library still loading.");return}
        if(scanJob?.isActive==true){send("status","A complete library scan is already running.");return}
        scanJob=scope.launch {
            try{
                val initial=withContext(Dispatchers.IO){store.prepareScan()}
                send("status",initial+". This may take a while for large ISP libraries.")
                var processed=0
                var totalNew=0
                var failures=0
                while(isActive){
                    var hadFolder=false
                    for(source in Sources.defaults){
                        if(!isActive)break
                        val url=withContext(Dispatchers.IO){store.nextFolder(source)}?:continue
                        hadFolder=true
                        val result=try{
                            CatalogScanner.scan(source,listOf(url),1)
                        }catch(e:Exception){
                            Log.w("ZAHRUS-TV","Scan failed: "+url,e)
                            ScanResult(emptyList(),emptyList(),0,1)
                        }
                        if(result.directories>0){
                            withContext(Dispatchers.IO){
                                store.completeFolder(source,url,result.remaining,result.items)
                            }
                            totalNew+=result.items.size
                        }else{
                            failures++
                            withContext(Dispatchers.IO){store.failFolder(source,url)}
                        }
                        processed++
                        if(processed%10==0){
                            val stats=withContext(Dispatchers.IO){store.scanStats()}
                            sendProgress(stats,"Scanning "+source.label+": "+processed+" folders checked")
                        }
                        if(processed%60==0){
                            sendCategories("Home")
                            sendHome()
                        }
                        delay(160L) // one polite directory request at a time
                    }
                    if(!hadFolder)break
                }
                if(isActive){
                    val stats=withContext(Dispatchers.IO){store.scanStats()}
                    sendProgress(stats,"Full scan pass completed")
                    send("status","Indexed "+stats.files+" files. "+stats.done+" folders checked, "+
                        stats.failed+" unreachable. Press Scan to retry unavailable directories.")
                    sendCategories("Home")
                    sendHome()
                }
            }catch(e:kotlinx.coroutines.CancellationException){
                send("status","Scan paused. Progress saved; press Scan All to continue.")
                throw e
            }catch(e:Exception){
                Log.e("ZAHRUS-TV","Scan interrupted",e)
                send("status","Scan paused due to an error. Press Scan All to resume.")
            }finally{
                scanJob=null
                val stats=runCatching{withContext(Dispatchers.IO){store.scanStats()}}.getOrNull()
                if(stats!=null)sendProgress(stats,"Scan stopped; progress saved")
            }
        }
    }
    private fun launchPlay(id:String){
        if(!initialized)return
        scope.launch{
            val item=withContext(Dispatchers.IO){store.get(id)}?:return@launch
            openPlayer(item)
        }
    }
    private fun openPlayer(item:MediaEntry){
        if(!allowedMedia(item.url)){
            Toast.makeText(this,"Unsupported video source",Toast.LENGTH_LONG).show();return
        }
        closePlayer()
        val exo=ExoPlayer.Builder(this).build()
        val container=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        val playerView=PlayerView(this).apply{player=exo;useController=true;isFocusable=true}
        container.addView(playerView,FrameLayout.LayoutParams(-1,-1))
        val exit=Button(this).apply{
            text="Exit Fullscreen"
            setOnClickListener{closePlayer();send("playback",item.id)}
        }
        container.addView(exit,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        exo.addListener(object:Player.Listener{
            override fun onPlayerError(error:PlaybackException){
                Toast.makeText(this@MainActivity,"Playback error: "+error.errorCodeName,
                    Toast.LENGTH_LONG).show()
            }
        })
        player=exo;playerContainer=container;playingId=item.id;playingUrl=item.url
        root.addView(container,FrameLayout.LayoutParams(-1,-1))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        playerView.requestFocus()
        exo.setMediaItem(MediaItem.fromUri(item.url))
        exo.prepare()
        val resume=prefs.getLong("resume_"+item.id,0L)
        if(resume>0)exo.seekTo(resume)
        exo.playWhenReady=true
    }
    private fun savePosition(){
        val id=playingId?:return
        val exo=player?:return
        prefs.edit().putLong("resume_"+id,exo.currentPosition.coerceAtLeast(0L)).apply()
    }
    private fun closePlayer(){
        savePosition()
        player?.release();player=null
        playerContainer?.let{if(::root.isInitialized)root.removeView(it)}
        playerContainer=null;playingId=null;playingUrl=null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_VISIBLE
        if(::web.isInitialized)web.requestFocus()
    }
    private fun showRecovery(message:String){
        runOnUiThread{
            if(!::root.isInitialized)root=FrameLayout(this)
            root.removeAllViews()
            val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
                setPadding(40,40,40,40);setBackgroundColor(Color.rgb(16,18,29))}
            panel.addView(TextView(this).apply{text="ZAHRUS TV — Recovery";textSize=26f;setTextColor(Color.WHITE)})
            panel.addView(TextView(this).apply{text=message+"\nYour saved data was not deleted.";textSize=19f;setTextColor(Color.LTGRAY)})
            panel.addView(Button(this).apply{text="Retry";setOnClickListener{recreate()}})
            root.addView(panel,FrameLayout.LayoutParams(-1,-1))
            setContentView(root)
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed(){
        if(player!=null){closePlayer();send("playback","")}else if(::web.isInitialized){
            web.evaluateJavascript("window.tvBack()",null)
        }else super.onBackPressed()
    }
    override fun onPause(){
        savePosition()
        player?.pause()
        super.onPause()
    }
    override fun onDestroy(){
        scanJob?.cancel()
        loadJob?.cancel()
        if(::root.isInitialized)closePlayer()
        if(::web.isInitialized){
            runCatching{web.removeJavascriptInterface("NativeTV")}
            runCatching{web.destroy()}
        }
        if(::store.isInitialized)runCatching{store.close()}
        scope.cancel()
        super.onDestroy()
    }
}