package com.cinestream.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.Gravity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebResourceResponse
import androidx.media3.ui.AspectRatioFrameLayout
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity:Activity(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val handler=Handler(Looper.getMainLooper())
    private val defaultInterval=6L*60*60*1000
    private val prefs by lazy{getSharedPreferences("cs_prefs",MODE_PRIVATE)}
    private lateinit var root:FrameLayout
    private lateinit var web:WebView
    private lateinit var db:CatalogDatabase
    private val items=linkedMapOf<String,MediaEntry>()
    private fun refreshMillis():Long=when(prefs.getString("opt_refresh","6 hours")){
        "Off"->0L;"1 hour"->3600000L;"3 hours"->10800000L;"12 hours"->43200000L;"24 hours"->86400000L
        else->21600000L
    }
    private var player:ExoPlayer?=null
    private var playerView:PlayerView?=null
    private var playerContainer:FrameLayout?=null
    private var fullscreen=true
    private var scaleMode="Fit"
    private val posters=ConcurrentHashMap<String,String>()
    private var playingId:String?=null
    private var scanning=false
    private var pageReady=false
    private val intervalRunner=object:Runnable{
        override fun run(){
            val delay=refreshMillis()
            if(delay>0&&!scanning&&System.currentTimeMillis()-prefs.getLong("sync",0L)>=delay)startScan()
            handler.postDelayed(this,60000L)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        db=CatalogDatabase(this);db.load().forEach{items[it.id]=it;it.poster?.let{poster->posters[it.id]=poster}}
        root=FrameLayout(this)
        val loader=WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/poster/",WebViewAssetLoader.PathHandler { path -> loadPoster(path) }).build()
        web=WebView(this).apply{
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.javaScriptCanOpenWindowsAutomatically=false
            webChromeClient=WebChromeClient()
            webViewClient=object:WebViewClientCompat(){
                override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?{
                    return loader.shouldInterceptRequest(request.url)
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                    return !(request.url.scheme=="https"&&request.url.host=="appassets.androidplatform.net" &&
                        request.url.path?.startsWith("/assets/")==true)
                }
                override fun onPageFinished(view:WebView,url:String){
                    if(url.startsWith("https://appassets.androidplatform.net/assets/")){
                        pageReady=true;send("settings",settingsJSON());send("catalog",catalogJSON())
                        send("status","Cached "+items.size+" files. Scans every 6 hours while app is open.")
                        val delay=refreshMillis()
                        if(delay>0&&(items.isEmpty()||System.currentTimeMillis()-prefs.getLong("sync",0L)>=delay))startScan()
                    }
                }
            }
            addJavascriptInterface(Bridge(),"NativeTV")
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
            isFocusableInTouchMode=true
            requestFocus()
        }
        root.addView(web,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
    }
    private fun send(type:String,value:String){
        runOnUiThread{
            if(pageReady){
                val js="window.fromNative("+JSONObject.quote(type)+","+JSONObject.quote(value)+")"
                web.evaluateJavascript(js,null)
            }
        }
    }
    private fun settingsJSON():String=JSONObject().apply{
        listOf("refresh","cardSize","showPosters","showGenre","showYear","playerMode","resume","defaultTab").forEach{key->
            val defaults=mapOf("refresh" to "6 hours","cardSize" to "Medium","showPosters" to "true",
                "showGenre" to "true","showYear" to "true","playerMode" to "Fit","resume" to "true","defaultTab" to "Home")
            put(key,prefs.getString("opt_"+key,defaults[key]))
        }
        val sources=JSONObject()
        Sources.defaults.forEach{source->sources.put(source.label,prefs.getBoolean("source_"+source.label,true))}
        put("sources",sources);put("lastSync",prefs.getLong("sync",0L));put("count",items.size)
    }.toString()
    private fun posterAllowed(url:String):Boolean = Sources.defaults.any{CatalogScanner.allowed(url,it.url)}&&
       Regex("(?i)\\.(jpg|jpeg|png|webp)$").containsMatchIn(url)
    private fun loadPoster(name:String):WebResourceResponse?{
        val id=name.substringBefore("?").substringBefore("/")
        if(!Regex("[a-f0-9]{24}").matches(id))return null
        val url=posters[id]?:return null
        if(!posterAllowed(url))return null
        val posterDirectory=File(cacheDir,"zahrus-posters");posterDirectory.mkdirs()
        val imageFile=File(posterDirectory,id+".img")
        val bytes=try{
            if(imageFile.exists()&&imageFile.length() in 1..3_000_000)imageFile.readBytes()
            else{
                val c=URL(url).openConnection() as HttpURLConnection
                c.connectTimeout=4000;c.readTimeout=6500;c.instanceFollowRedirects=false
                try{
                    if(c.responseCode!=200||c.contentLengthLong>3_000_000L)return null
                    val data=c.inputStream.use{it.readNBytes(3_000_001)}
                    if(data.isEmpty()||data.size>3_000_000)return null
                    imageFile.writeBytes(data)
                    data
                }finally{c.disconnect()}
            }
        }catch (_:Exception){return null}
        val mime=when{url.endsWith(".png",true)->"image/png";url.endsWith(".webp",true)->"image/webp";else->"image/jpeg"}
        return WebResourceResponse(mime,null,ByteArrayInputStream(bytes))
    }
    private fun catalogJSON():String{
        val a=JSONArray()
        items.values.sortedByDescending{it.modified}.forEach{v->
            a.put(JSONObject().apply{
                put("id",v.id);put("title",v.title);put("url",v.url)
                put("category",v.category);put("kind",v.kind);put("modified",v.modified)
                put("size",v.size);put("year",v.year?:"");put("poster",if(v.poster!=null)"https://appassets.androidplatform.net/poster/"+v.id else "")
                put("genre",v.genre?:"");put("quality",v.quality?:"")
                put("season",v.season?:JSONObject.NULL);put("episode",v.episode?:JSONObject.NULL)
                put("favorite",prefs.getBoolean("fav_"+v.id,false))
                put("position",prefs.getLong("resume_"+v.id,0L))
            })
        }
        return a.toString()
    }
    inner class Bridge{
        @JavascriptInterface fun ready()=runOnUiThread{send("settings",settingsJSON());send("catalog",catalogJSON())}
        @JavascriptInterface fun refresh()=runOnUiThread{startScan()}
        @JavascriptInterface fun changeSetting(key:String,value:String)=runOnUiThread{
            val allowed=mapOf(
                "refresh" to setOf("Off","1 hour","3 hours","6 hours","12 hours","24 hours"),
                "cardSize" to setOf("Small","Medium","Large"),
                "showPosters" to setOf("true","false"),"showGenre" to setOf("true","false"),
                "showYear" to setOf("true","false"),"playerMode" to setOf("Fit","Fill","Zoom"),
                "resume" to setOf("true","false"),
                "defaultTab" to setOf("Home","Movies","Series","Latest","Favorites"))
            if(value in (allowed[key]?:emptySet())){
                prefs.edit().putString("opt_"+key,value).apply()
                send("settings",settingsJSON())
            }
        }
        @JavascriptInterface fun changeSource(name:String,enabled:Boolean)=runOnUiThread{
            if(Sources.defaults.any{it.label==name}){
                prefs.edit().putBoolean("source_"+name,enabled).apply()
                send("settings",settingsJSON())
            }
        }
        @JavascriptInterface fun clearPosterCache()=runOnUiThread{
            File(cacheDir,"zahrus-posters").listFiles()?.forEach{it.delete()}
            send("status","Poster cache cleared.");send("catalog",catalogJSON())
        }
        @JavascriptInterface fun clearCatalog()=runOnUiThread{
            if(!scanning){db.clear();items.clear();posters.clear()
                Sources.defaults.forEach{prefs.edit().remove("queue_"+it.label.replace(Regex("[^A-Za-z0-9]"),"_")).apply()}
                prefs.edit().remove("sync").apply();send("catalog",catalogJSON());send("settings",settingsJSON())
                send("status","Catalog cleared. Press Scan to index media again.")}
        }
        @JavascriptInterface fun play(id:String)=runOnUiThread{items[id]?.let{openPlayer(it)}}
        @JavascriptInterface fun favorite(id:String,value:Boolean)=runOnUiThread{
            if(items.containsKey(id)){
                prefs.edit().putBoolean("fav_"+id,value).apply()
                send("catalog",catalogJSON())
            }
        }
        @JavascriptInterface fun external(id:String)=runOnUiThread{
            val item=items[id]?:return@runOnUiThread
            if(Sources.defaults.none{CatalogScanner.allowed(item.url,it.url)})return@runOnUiThread
            try{
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.url),"video/*"))
            }catch (_:Exception){Toast.makeText(this@MainActivity,"No external video player installed",Toast.LENGTH_LONG).show()}
        }
    }
    private fun pending(key:String):List<String> = try {
        val a=JSONArray(prefs.getString("queue_"+key,"[]"))
        (0 until a.length()).map{a.getString(it)}
    }catch (_:Exception){emptyList()}
    private fun startScan(){
        if(scanning){send("status","Already scanning. Please wait.");return}
        scanning=true
        send("status","Scanning media directories (Movies and Series only)...")
        scope.launch {
            var reached=0;var discovered=0
            for(source in Sources.defaults){
                if(!prefs.getBoolean("source_"+source.label,true))continue
                val key=source.label.replace(Regex("[^A-Za-z0-9]"),"_")
                try{
                    val result=CatalogScanner.scan(source,pending(key),24)
                    result.items.forEach{newItem->
                        val savedPoster=newItem.poster ?: items.values.firstOrNull { old ->
                            old.title==newItem.title && old.category==newItem.category && old.poster!=null
                        }?.poster
                        val item=if(savedPoster!=newItem.poster)newItem.copy(poster=savedPoster) else newItem
                        items[item.id]=item
                        item.poster?.let{poster->posters[item.id]=poster}
                    }
                    withContext(Dispatchers.IO){db.save(result.items.map{items[it.id]?:it})}
                    discovered+=result.items.size
                    if(result.directories>0)reached++
                    prefs.edit().putString("queue_"+key,JSONArray(result.remaining).toString()).apply()
                    send("catalog",catalogJSON())
                    send("status",source.label+": "+result.directories+" dirs, "+result.items.size+
                        " videos; "+result.remaining.size+" folders left in queue")
                }catch(e:Exception){send("status",source.label+": "+(e.message?:"Connection failed"))}
            }
            if(reached>0)prefs.edit().putLong("sync",System.currentTimeMillis()).apply()
            scanning=false
            send("settings",settingsJSON())
            send("complete","Scan completed. "+items.size+" cached videos; "+
                reached+"/"+Sources.defaults.size+" sources reached. Refresh after 6 hours while open.")
        }
    }
    private fun immersive(enabled:Boolean){
        @Suppress("DEPRECATION")
        val flags=if(enabled) (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else View.SYSTEM_UI_FLAG_VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=flags
    }
    private fun button(label:String, action:()->Unit):Button=Button(this).apply{
        text=label;textSize=15f;setTextColor(Color.WHITE)
        background=GradientDrawable().apply{setColor(Color.rgb(28,34,52));cornerRadius=14f}
        setPadding(20,10,20,10);isFocusable=true
        setOnClickListener{action()}
    }
    private fun resizeMode(mode:String):Int=when(mode){
        "Fill"->AspectRatioFrameLayout.RESIZE_MODE_FILL
        "Zoom"->AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else->AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    private fun setScale(mode:String){
        scaleMode=mode
        playerView?.resizeMode=resizeMode(mode)
        prefs.edit().putString("opt_playerMode",mode).apply()
    }
    private fun updatePlayerLayout(){
        val p=playerView?:return
        val params=if(fullscreen)FrameLayout.LayoutParams(-1,-1)
        else FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*0.86).toInt(),
            (resources.displayMetrics.heightPixels*0.80).toInt(),Gravity.CENTER)
        p.layoutParams=params
        immersive(fullscreen)
    }
    private fun openPlayer(item:MediaEntry){
        if(Sources.defaults.none{CatalogScanner.allowed(item.url,it.url)}){
            Toast.makeText(this,"Invalid video source",Toast.LENGTH_LONG).show();return
        }
        closePlayer()
        val exo=ExoPlayer.Builder(this).build()
        val container=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        val playerUi=PlayerView(this).apply{player=exo;useController=true;isFocusable=true}
        container.addView(playerUi,FrameLayout.LayoutParams(-1,-1))
        val tools=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(6,6,12,6)
            setBackgroundColor(Color.argb(180,12,16,27))
        }
        val fullButton=button("⛶ Fullscreen"){fullscreen=!fullscreen;updatePlayerLayout()}
        val fitButton=button("Fit / Fill / Zoom"){
            val next=when(scaleMode){"Fit"->"Fill";"Fill"->"Zoom";else->"Fit"}
            setScale(next)
            Toast.makeText(this,"Display mode: "+next,Toast.LENGTH_SHORT).show()
        }
        val exitButton=button("✕ Exit"){closePlayer();send("catalog",catalogJSON())}
        listOf(fullButton,fitButton,exitButton).forEach{
            tools.addView(it,LinearLayout.LayoutParams(-2,-2).apply{marginStart=8})
        }
        container.addView(tools,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END))
        exo.addListener(object:Player.Listener{
            override fun onPlayerError(error:PlaybackException){
                Toast.makeText(this@MainActivity,"Playback error: "+error.errorCodeName+
                    ". Try external player.",Toast.LENGTH_LONG).show()
            }
        })
        player=exo;playerView=playerUi;playerContainer=container;playingId=item.id
        fullscreen=true
        scaleMode=prefs.getString("opt_playerMode","Fit")?:"Fit"
        playerUi.resizeMode=resizeMode(scaleMode)
        web.visibility=View.GONE
        root.addView(container,FrameLayout.LayoutParams(-1,-1))
        updatePlayerLayout()
        playerUi.requestFocus()
        exo.setMediaItem(ExoMediaItem.fromUri(item.url));exo.prepare()
        val pos=if(prefs.getString("opt_resume","true")=="true")prefs.getLong("resume_"+item.id,0L)else 0L
        if(pos>0)exo.seekTo(pos)
        exo.playWhenReady=true
    }
    private fun savePosition(){
        val id=playingId?:return
        val exo=player?:return
        prefs.edit().putLong("resume_"+id,exo.currentPosition.coerceAtLeast(0L)).apply()
    }
    private fun closePlayer(){
        savePosition();player?.release();player=null
        playerContainer?.let{root.removeView(it)};playerContainer=null;playerView=null;playingId=null
        immersive(false)
        if(::web.isInitialized){web.visibility=View.VISIBLE;web.requestFocus()}
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed(){
        if(player!=null){closePlayer();send("catalog",catalogJSON())}
        else web.evaluateJavascript("window.tvBack()",null)
    }
    override fun onResume(){
        super.onResume()
        handler.removeCallbacks(intervalRunner)
        handler.postDelayed(intervalRunner,60000L)
    }
    override fun onPause(){
        savePosition();player?.pause()
        handler.removeCallbacks(intervalRunner)
        super.onPause()
    }
    override fun onDestroy(){
        handler.removeCallbacks(intervalRunner)
        closePlayer()
        web.removeJavascriptInterface("NativeTV")
        web.destroy();db.close();scope.cancel()
        super.onDestroy()
    }
}