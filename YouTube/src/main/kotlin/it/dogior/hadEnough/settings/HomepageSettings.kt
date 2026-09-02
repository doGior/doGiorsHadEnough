package it.dogior.hadEnough.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.View.generateViewId
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import it.dogior.hadEnough.YouTubePlugin
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.view.isInvisible
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import it.dogior.hadEnough.BuildConfig
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import kotlin.collections.forEach

/**
 * A simple [Fragment] subclass.
 * Use the [HomepageSettings] factory method to
 * create an instance of this fragment.
 */
class HomepageSettings(
    private val plugin: YouTubePlugin,
    val sharedPref: SharedPreferences?,
) :
    BottomSheetDialogFragment() {

    companion object{
        fun migrateFromSetPreferences(playlistsSet: Set<String>): List<HomeSection> {
            val homeSections = mutableListOf<HomeSection>()
            val oldList = playlistsSet.mapNotNull { tryParseJson<Triple<String, String, Long>>(it) }
            oldList.sortedBy { it.third }.forEachIndexed { index, triple ->
                homeSections.add(
                    HomeSection(name = triple.second, url = triple.first, position = index)
                )
            }
            return homeSections
        }
    }
    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    private fun getDrawable(name: String): Drawable? {
        val id =
            plugin.resources!!.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return ResourcesCompat.getDrawable(plugin.resources!!, id, null)
    }

    private fun getString(name: String): String? {
        val id =
            plugin.resources!!.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return plugin.resources!!.getString(id)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        val id = plugin.resources!!.getIdentifier(
            "homepage_settings",
            "layout",
            BuildConfig.LIBRARY_PACKAGE_NAME
        )
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val headerTw = view.findView<TextView>("header_tw")
        headerTw.text = getString("changeHomepageHeader_tw")

        val trendingSwitch = view.findView<Switch>("trending_switch")
        trendingSwitch.text = getString("trending_text")
        trendingSwitch.isChecked = sharedPref?.getBoolean("trending", true) ?: true

        val addPlaylistTw = view.findView<TextView>("addPlaylist_tw")
        addPlaylistTw.text = getString("addPlaylist_tw")

        val youtubeUrlEt = view.findView<TextView>("youtubeUrl_editText")
        youtubeUrlEt.hint = getString("add_playlist_hint")

        val savedList = mutableListOf<HomeSection>()
        sharedPref?.getString("savedLists", null)?.let {
            Log.d("YoutubeSettings", "Playlists: $it")
            val saves = parseJson<List<HomeSection>>(it)
            savedList.removeAll { true }
            savedList.addAll(saves)
            savedList.sortBy { item -> item.position }

        } ?: sharedPref?.getStringSet("playlists", emptySet())?.let {
            Log.d("YoutubeSettings", "Playlists set: $it")
            savedList.addAll(migrateFromSetPreferences(it))

            with(sharedPref?.edit()) {
                this?.putString("savedLists", savedList.toJson())
                this?.apply()
            }
        }
        val playlistsList = view.findView<LinearLayout>("playlists_list")
        renderList(savedList, playlistsList)


        val addSectionButton = view.findView<ImageButton>("addSection_button")
        addSectionButton.setImageDrawable(getDrawable("add_icon"))
        addSectionButton.makeTvCompatible()

        addSectionButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                addSectionButton.isClickable = false
                GlobalScope.launch {
                    val item = try {
                        HomeSection(
                            name = getName(youtubeUrlEt.text.toString()) ?: "Unknown",
                            url = youtubeUrlEt.text.toString(),
                            position = savedList.maxOf { it.position } + 1
                        )
                    } catch (_: NoSuchMethodError) {
                        addSectionButton.isClickable = true
                        showToast("Error")
                        return@launch
                    }

                    Log.d("YoutubeProvider", item.toJson())
                    sharedPref?.getString("savedLists", null)?.let {
                        val saves = parseJson<List<HomeSection>>(it)
                        savedList.removeAll { true }
                        savedList.addAll(saves)
                        savedList.add(item)
                    }
                    with(sharedPref?.edit()) {
                        this?.putString("savedLists", savedList.toJson())
                        this?.apply()
                    }
                    withContext(Dispatchers.Main) {
                        youtubeUrlEt.text = ""
                        addSectionButton.isClickable = true
                        renderList(savedList, playlistsList)
                    }
                }
            }
        })


        val saveButton = view.findView<ImageButton>("save_button")
        saveButton.setImageDrawable(getDrawable("save_icon"))
        saveButton.makeTvCompatible()

        saveButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                with(sharedPref?.edit()) {
                    this?.putBoolean("trending", trendingSwitch.isChecked)
                    this?.putString("savedLists", savedList.toJson())
                    this?.apply()
                }
                showToast("Saved")
                dismiss()
            }
        })

    }

    private fun playlistsRow(
        item: HomeSection,
        sharedPref: SharedPreferences?,
        saves: MutableList<HomeSection>,
        playlistList: LinearLayout,
    ): RelativeLayout {
        val title = item.name
        val maxIndex = saves.maxOf { it.position }
        // Create the RelativeLayout
        val relativeLayout = RelativeLayout(this@HomepageSettings.requireContext()).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setPadding(
                    0,
                    0,
                    0,
                    dpToPx(this@HomepageSettings.requireContext(), 8)
                ) // Convert dp to px
            }
        }

        // Create the TextView (Label)
        val label = TextView(this.context).apply {
            text = title
            textSize = 15f
        }

        val moveUpButton = ImageButton(this.context).apply {
            id = generateViewId()
            setImageDrawable(getDrawable("triangle"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        val moveDownButton = ImageButton(this.context).apply {
            id = generateViewId()
            setImageDrawable(getDrawable("triangle_down"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        val deleteButton = ImageButton(this.context).apply {
            id = generateViewId()
            setImageDrawable(getDrawable("delete_icon"))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }

        val labelParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
            .apply {
                addRule(RelativeLayout.CENTER_VERTICAL) // Vertically center in parent
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.LEFT_OF, moveUpButton.id)
                marginEnd = dpToPx(this@HomepageSettings.requireContext(), 8)
            }


        val moveUpButtonParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
            .apply {
                if (item.position == maxIndex){
                    addRule(RelativeLayout.LEFT_OF, deleteButton.id)
                }else{
                    addRule(RelativeLayout.LEFT_OF, moveDownButton.id)
                }

                addRule(RelativeLayout.CENTER_VERTICAL) // Vertically center in parent
                marginEnd = dpToPx(this@HomepageSettings.requireContext(), 8) // Convert dp to px
            }
        moveUpButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
//                Log.d("YoutubeSettings", item.toJson())
                moveRow(item, -1, saves)?.let{ updatedList ->
                    renderList(updatedList, playlistList)
//                    Log.d("YoutubeSettings", updatedList.toJson())
                }
            }
        })

        val moveDownButtonParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
            .apply {
                addRule(RelativeLayout.LEFT_OF, deleteButton.id)
                addRule(RelativeLayout.CENTER_VERTICAL) // Vertically center in parent
                marginEnd = dpToPx(this@HomepageSettings.requireContext(), 8) // Convert dp to px
            }
        moveDownButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
//                Log.d("YoutubeSettings", item.toJson())
                moveRow(item, 1, saves)?.let{ updatedList ->
                    renderList(updatedList, playlistList)
//                    Log.d("YoutubeSettings", updatedList.toJson())
                }
            }
        })


        val deleteButtonParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
            .apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL) // Vertically center in parent
                marginEnd = dpToPx(this@HomepageSettings.requireContext(), 8) // Convert dp to px
                marginStart = dpToPx(this@HomepageSettings.requireContext(), 2)
            }
        deleteButton.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                val deleteSuccessfull = saves.remove(item)
                if (deleteSuccessfull) {
                    with(sharedPref?.edit()) {
                        this?.putString("savedLists", saves.toJson())
                        this?.apply()
                    }
                    playlistList.removeView(relativeLayout)
                    showToast("$title removed")
                } else {
                    showToast("Error removing $title")
                }
            }
        })
        if (item.position == 0){
            moveUpButton.isInvisible = true
        }
        if (item.position == maxIndex){
            moveDownButton.isInvisible = true
        }

        relativeLayout.addView(label, labelParams)
        relativeLayout.addView(moveUpButton, moveUpButtonParams)
        relativeLayout.addView(moveDownButton, moveDownButtonParams)
        relativeLayout.addView(deleteButton, deleteButtonParams)

        return relativeLayout
    }

    private suspend fun getName(playlistUrl: String): String? {
        val urlPath = playlistUrl.substringAfter("youtu").substringAfter("/")
        val isPlaylist = urlPath.startsWith("playlist?list=")
        val isChannel = urlPath.startsWith("@") || urlPath.startsWith("channel")

        return withContext(Dispatchers.IO) {
            if (isPlaylist && !isChannel) {
                val playlistInfo = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
                "${playlistInfo.uploaderName}: ${playlistInfo.name}"
            } else if (!isPlaylist && isChannel) {
                ChannelInfo.getInfo(ServiceList.YouTube, playlistUrl).name
            } else {
                "Unknown"
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }

    /* direction HAS TO BE either 1 (to move down) or -1 (to move up) */
    private fun moveRow(
        item: HomeSection,
        direction: Int,
        savedList: MutableList<HomeSection>
    ): MutableList<HomeSection>? {
        savedList.remove(item)

        val targetPosition = item.position + direction

//        Log.d("YoutubeSettings", "Item: ${item.name}\nStarting Position: ${item.position}\nTarget Position: $targetPosition")
        if (targetPosition < 0 || targetPosition > savedList.maxOf { it.position }){
            return null
        }
        val itemToSwap = savedList.firstOrNull { it.position == targetPosition }
        itemToSwap?.position = item.position
        item.position = targetPosition
        savedList.add(item)
//        Log.d("YoutubeSettings",savedList.sortedBy{it.position}.toJson())
        return savedList
    }

    private fun renderList(savedList: MutableList<HomeSection>, playlistsList: LinearLayout) {
        playlistsList.removeAllViews()
        savedList.sortedBy { it.position }.forEach {
            playlistsList.addView(
                playlistsRow(it, sharedPref, savedList, playlistsList)
            )
        }
    }

    data class HomeSection(
        val name: String,
        val url: String,
        var position: Int
    )
}
