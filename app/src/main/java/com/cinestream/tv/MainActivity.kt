package com.cinestream.tv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
    private val interval=6L*60*60*1000
    private val prefs by lazy{getSharedPreferences("cs_prefs",MODE_PRIVATE)}
    private lateinit var root:FrameLayout
    private lateinit var web:WebView
    private lateinit var db:CatalogDatabase
    private val items=linkedMapOf<String,MediaEntry>()
    private var player:ExoPlayer?=null
    private var playerView:PlayerView?=null
    private var playingId:String?=null
    private var scanning=false
    private var pageReady=false
    private val intervalRunner=object:Runnable{
        override fun run(){
            if(!scanning&&System.currentTimeMillis()-prefs.getLong("sync",0L)>=interval)startScan()
            handler.postDelayed(this,interval)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        db=CatalogDatabase(this);db.load().forEach{items[it.id]=it}
        root=FrameLayout(this)
        val loader=WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
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
                        pageReady=true;send("catalog",catalogJSON())
                        send("status","Cached "+items.size+" files. Scans every 6 hours while app is open.")
                        if(items.isEmpty()||System.currentTimeMillis()-prefs.getLong("sync",0L)>=interval)startScan()
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
    private fun catalogJSON():String{
        val a=JSONArray()
        items.values.sortedByDescending{it.modified}.forEach{v->
            a.put(JSONObject().apply{
                put("id",v.id);put("title",v.title);put("url",v.url)
                put("category",v.category);put("kind",v.kind);put("modified",v.modified)
                put("size",v.size);put("year",v.year?:"");put("poster",v.poster?:"")
                put("season",v.season?:JSONObject.NULL);put("episode",v.episode?:JSONObject.NULL)
                put("favorite",prefs.getBoolean("fav_"+v.id,false))
                put("position",prefs.getLong("resume_"+v.id,0L))
            })
        }
        return a.toString()
    }
    inner class Bridge{
        @JavascriptInterface fun ready()=runOnUiThread{send("catalog",catalogJSON())}
        @JavascriptInterface fun refresh()=runOnUiThread{startScan()}
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
    private fun pending(key:String):List<String>=try{
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
                val key=source.label.replace(Regex("[^A-Za-z0-9]"),"_")
                try{
                    val result=CatalogScanner.scan(source,pending(key),24)
                    result.items.forEach{items[it.id]=it}
                    withContext(Dispatchers.IO){db.save(result.items)}
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
            send("complete","Scan completed. "+items.size+" cached videos; "+
                reached+"/"+Sources.defaults.size+" sources reached. Refresh after 6 hours while open.")
        }
    }
    private fun openPlayer(item:MediaEntry){
        if(Sources.defaults.none{CatalogScanner.allowed(item.url,it.url)}){
            Toast.makeText(this,"Invalid video source",Toast.LENGTH_LONG).show();return
        }
        closePlayer()
        val exo=ExoPlayer.Builder(this).build()
        val playerUi=PlayerView(this).apply{player=exo;useController=true;isFocusable=true}
        exo.addListener(object:Player.Listener{
            override fun onPlayerError(error:PlaybackException){
                Toast.makeText(this@MainActivity,"Playback error: "+error.errorCodeName+
                    ". Try external player.",Toast.LENGTH_LONG).show()
            }
        })
        player=exo;playerView=playerUi;playingId=item.id
        root.addView(playerUi,FrameLayout.LayoutParams(-1,-1))
        playerUi.requestFocus()
        exo.setMediaItem(ExoMediaItem.fromUri(item.url));exo.prepare()
        val pos=prefs.getLong("resume_"+item.id,0L)
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
        playerView?.let{root.removeView(it)};playerView=null;playingId=null
        if(::web.isInitialized)web.requestFocus()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed(){
        if(player!=null){closePlayer();send("catalog",catalogJSON())}
        else web.evaluateJavascript("window.tvBack()",null)
    }
    override fun onResume(){
        super.onResume()
        handler.removeCallbacks(intervalRunner)
        handler.postDelayed(intervalRunner,interval)
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