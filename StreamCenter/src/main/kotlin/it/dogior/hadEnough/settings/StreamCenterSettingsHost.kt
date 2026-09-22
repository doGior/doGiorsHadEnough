package it.dogior.hadEnough.settings

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import it.dogior.hadEnough.StreamCenterPlugin

private class SettingsHostFrame(
    context: Context,
    private val onInteraction: () -> Unit,
) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteraction()
        return super.dispatchTouchEvent(event)
    }
}

class StreamCenterSettings : StreamCenterBaseSettingsFragment() {
    private companion object {
        val CONTAINER_ID = View.generateViewId()
        const val MENU_TAG = "StreamCenterMenu"
    }

    private var backButton: TextView? = null
    private var titleView: TextView? = null
    private var buildInfoRow: LinearLayout? = null
    private var actionSlot: FrameLayout? = null
    private var searchField: EditText? = null
    private var clearButton: TextView? = null
    private var resultsScroll: ScrollView? = null
    private var resultsList: LinearLayout? = null
    private var contentContainer: FrameLayout? = null
    private var searchChangedSettings = false
    private var returningToPreviousScreen = false

    private val screenLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fragmentManager: FragmentManager,
            fragment: Fragment,
            view: View,
            savedInstanceState: Bundle?,
        ) {
            syncBar()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        resetRestartNeeded()
        val frameHeight = standardSubmenuMinimumHeight()

        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(buildBar())
        contentContainer = FrameLayout(requireContext()).apply {
            id = CONTAINER_ID
        }
        resultsList = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = if (isTvLikeDevice()) dp(30) else dp(16)
            setPadding(horizontal, dp(4), horizontal, dp(18))
        }
        resultsScroll = ScrollView(requireContext()).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            visibility = View.GONE
            addView(
                resultsList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val stack = FrameLayout(requireContext()).apply {
            addView(
                contentContainer,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                resultsScroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        column.addView(stack)

        return SettingsHostFrame(requireContext()) { onSheetInteraction() }.apply {
            background = sheetFrameBackground()
            clipToOutline = true
            addView(
                createParticleBackground(showOrbitalDecoration = true),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                column,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    frameHeight,
                ),
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                frameHeight,
            )
        }
    }

    private fun buildBar(): View {
        val titleRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backButton = TextView(requireContext()).apply {
            text = "‹"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "Torna indietro"
            isClickable = true
            isFocusable = true
            setTextColor(Color.parseColor(COLOR_ACCENT))
            background = interactiveBackground(
                tint(COLOR_ACCENT, "12"),
                COLOR_ACCENT,
                999,
                strokeColor = tint(COLOR_ACCENT, "4D"),
            )
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
            setOnClickListener { pop() }
        }.also(titleRow::addView)
        titleView = titleText("StreamCenter", 21, true, display = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }.also(titleRow::addView)
        buildInfoRow = buildHeaderInfo().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }.also(titleRow::addView)
        actionSlot = FrameLayout(requireContext()).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10) }
        }.also(titleRow::addView)

        val searchRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(6), dp(2))
            background = outlined(COLOR_STROKE, COLOR_INPUT_FILL, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        searchRow.addView(
            TextView(requireContext()).apply {
                text = "⌕"
                textSize = 18f
                includeFontPadding = false
                setTextColor(Color.parseColor(tint(COLOR_ACCENT, "B0")))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(9) }
            },
        )
        searchField = EditText(requireContext()).apply {
            hint = "Cerca un'impostazione"
            setHintTextColor(Color.parseColor(COLOR_MUTED))
            setTextColor(Color.parseColor(COLOR_TEXT))
            textSize = 14f
            setSingleLine(true)
            maxLines = 1
            background = null
            setPadding(0, dp(11), 0, dp(11))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            )
            doAfterTextChanged { editable -> renderSearch(editable?.toString().orEmpty()) }
        }.also(searchRow::addView)
        clearButton = TextView(requireContext()).apply {
            text = "✕"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "Cancella la ricerca"
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setTextColor(Color.parseColor(COLOR_MUTED))
            background = interactiveBackground(
                tint(COLOR_MUTED, "12"),
                COLOR_ACCENT,
                999,
                strokeColor = tint(COLOR_MUTED, "44"),
            )
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(6) }
            setOnClickListener { clearSearch() }
        }.also(searchRow::addView)

        val separator = View(requireContext()).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor(tint(COLOR_ACCENT, "3A")),
                    Color.TRANSPARENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply { topMargin = dp(12) }
        }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#191210"))
                val radius = dp(22).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            val horizontal = if (isTvLikeDevice()) dp(30) else dp(16)
            setPadding(horizontal, dp(16), horizontal, 0)
            addView(titleRow)
            addView(searchRow)
            addView(separator)
        }
    }

    private fun buildHeaderInfo(): LinearLayout {
        val infoLines = StreamCenterPlugin.getBuildInfoText().replace('\u00A0', ' ').lines()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            listOf("Commit" to HeaderInfoEffectStyle.COMMIT, "Build" to HeaderInfoEffectStyle.BUILD)
                .forEach { (label, style) ->
                    val value = infoLines.firstOrNull { it.startsWith("$label ") }
                        ?.removePrefix("$label ") ?: return@forEach
                    addView(headerInfoBadge(label, value, style).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(5), dp(3), dp(5), dp(3))
                        contentDescription = "$label $value"
                        (getChildAt(0) as TextView).apply {
                            textSize = 9f
                            maxLines = 1
                        }
                        (getChildAt(1) as TextView).apply {
                            setPadding(0, dp(1), 0, 0)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER
                            maxWidth = dp(if (label == "Build") 114 else 48)
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, dp(14),
                            )
                            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                                this, 8, 10, 1, TypedValue.COMPLEX_UNIT_SP,
                            )
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { if (label == "Build") marginStart = dp(5) }
                    })
                }
            if (childCount == 0) addView(bodyText(infoLines.firstOrNull().orEmpty(), 11).apply {
                gravity = Gravity.END
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(CONTAINER_ID) != null) return
        childFragmentManager.beginTransaction()
            .add(CONTAINER_ID, StreamCenterMenuFragment(), MENU_TAG)
            .commit()
    }

    fun push(fragment: StreamCenterBaseSettingsFragment, tag: String) {
        if (childFragmentManager.isStateSaved) return
        val alreadyOpen = currentScreen()?.tag == tag
        if (!alreadyOpen) searchChangedSettings = false
        clearSearch()
        if (alreadyOpen) return
        returningToPreviousScreen = false
        StreamCenterSettingsNavigation.configure(childFragmentManager.beginTransaction())
            .replace(CONTAINER_ID, fragment, tag)
            .addToBackStack(tag)
            .commit()
    }

    fun pop(): Boolean {
        if (childFragmentManager.backStackEntryCount == 0) return false
        if (childFragmentManager.isStateSaved) return true
        returningToPreviousScreen = true
        childFragmentManager.popBackStack()
        return true
    }

    private fun isSearching(): Boolean = !searchField?.text.isNullOrBlank()

    private fun clearSearch() {
        val field = searchField ?: return
        if (field.text.isNullOrEmpty()) return
        field.setText("")
    }

    private fun renderSearch(query: String) {
        val list = resultsList ?: return
        val scroll = resultsScroll ?: return
        val container = contentContainer ?: return
        clearButton?.visibility = if (query.isBlank()) View.GONE else View.VISIBLE
        list.removeAllViews()
        if (query.isBlank()) {
            scroll.visibility = View.GONE
            container.visibility = View.VISIBLE
            if (searchChangedSettings) {
                searchChangedSettings = false
                reloadCurrentScreen()
            }
            return
        }
        container.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        val matches = StreamCenterSettingsIndex.search(sharedPref, query)
        if (matches.isEmpty()) {
            list.addView(emptySearchState(query))
            return
        }
        matches.take(24).forEach { entry -> list.addView(searchResultRow(entry)) }
    }

    private fun emptySearchState(query: String): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(26), dp(18), dp(26))
            background = cardBackground(COLOR_CARD_ALT, tint(COLOR_ACCENT, "33"), 16)
            layoutParams = verticalParams(top = 10)
            addView(
                titleText("Nessun risultato per “$query”", 15, true).apply {
                    gravity = Gravity.CENTER
                },
            )
            addView(
                bodyText("Prova con il nome della fonte, della sezione o di cosa vuoi cambiare.", 12).apply {
                    gravity = Gravity.CENTER
                    alpha = 0.78f
                    setPadding(0, dp(6), 0, 0)
                },
            )
        }
    }

    private fun searchResultRow(entry: SettingsIndexEntry): View {
        val toggle = entry.toggle
        if (toggle != null) {
            return switchRow(
                title = entry.title,
                summary = entry.trail,
                checked = toggle.read(sharedPref),
                defaultChecked = toggle.defaultChecked,
                accent = entry.accent,
                icon = entry.icon,
                leadingView = entry.installedExtension?.let(::installedExtensionBadge),
                topMargin = 8,
                dimWhenOff = false,
            ) { enabled ->
                toggle.write(sharedPref, enabled)
                searchChangedSettings = true
                refreshVisibleSettingsEffects()
            }
        }
        val trail = bodyText(entry.trail, 11).apply { alpha = 0.72f }
        val arrow = chevron(entry.accent)
        return settingsRow(
            title = entry.title,
            icon = entry.icon,
            accent = entry.accent,
            fillColor = COLOR_CARD_ALT,
            strokeColor = tint(entry.accent, "5A"),
            summaryView = trail,
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            topMargin = 8,
        ) { openIndexedScreen(entry.screen) }.view
    }

    private fun openIndexedScreen(screen: SettingsScreenId) {
        when (screen) {
            SettingsScreenId.BASE_CATALOG ->
                push(StreamCenterBaseCatalogSettingsFragment(), "StreamCenterBaseCatalogSettings")
            SettingsScreenId.HOME ->
                push(StreamCenterHomeSettingsFragment(), "StreamCenterHomeSettings")
            SettingsScreenId.SOURCES ->
                push(StreamCenterSourcesSettingsFragment(), "StreamCenterSourcesSettings")
            SettingsScreenId.INTERFACE ->
                push(StreamCenterInterfaceSettingsFragment(), "StreamCenterInterfaceSettings")
            SettingsScreenId.SYSTEM ->
                push(StreamCenterSupportSettingsFragment(), "StreamCenterSupportSettings")
            SettingsScreenId.CACHE ->
                push(StreamCenterCacheSettingsFragment(), "StreamCenterCacheSettings")
            SettingsScreenId.LOGS ->
                push(StreamCenterLogsSettingsFragment(), "StreamCenterLogsSettings")
        }
    }

    private fun currentScreen(): StreamCenterBaseSettingsFragment? {
        return childFragmentManager.findFragmentById(CONTAINER_ID) as? StreamCenterBaseSettingsFragment
    }

    private fun reloadCurrentScreen() {
        if (childFragmentManager.isStateSaved) return
        val screen = currentScreen() ?: return
        childFragmentManager.beginTransaction()
            .detach(screen)
            .attach(screen)
            .commit()
    }

    private fun syncBar() {
        val screen = currentScreen()
        val nested = childFragmentManager.backStackEntryCount > 0
        buildInfoRow?.visibility = if (nested) View.GONE else View.VISIBLE
        backButton?.visibility = if (nested) View.VISIBLE else View.GONE
        val accent = screen?.screenAccent ?: COLOR_ACCENT
        val title = screen?.screenTitle ?: "StreamCenter"
        titleView?.apply {
            val titleChanged = text.toString() != title
            (layoutParams as LinearLayout.LayoutParams).apply {
                width = if (nested) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
                weight = if (nested) 1f else 0f
            }
            requestLayout()
            text = title
            setTextColor(Color.parseColor(if (nested) accent else COLOR_TEXT))
            applyTitleEffect(this, title, accent)
            if (titleChanged) {
                animate().cancel()
                alpha = if (reduceMotion) 1f else 0f
                translationY = if (reduceMotion) 0f else dp(if (returningToPreviousScreen) -5 else 5).toFloat()
                if (!reduceMotion) animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
        val slot = actionSlot ?: return
        slot.removeAllViews()
        val action = screen?.screenAction()
        slot.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action == null) return
        slot.addView(
            actionButton(action.label, accent, action.onInvoke).apply {
                contentDescription = action.description
                textSize = 12f
                minWidth = dp(76)
                minimumHeight = dp(38)
                setPadding(dp(14), dp(7), dp(14), dp(7))
            },
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return StreamCenterSettingsDialog(requireContext(), theme) {
            when {
                isSearching() -> {
                    clearSearch()
                    true
                }
                else -> pop()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        childFragmentManager.registerFragmentLifecycleCallbacks(screenLifecycleCallbacks, false)
        syncBar()
    }

    override fun onStop() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(screenLifecycleCallbacks)
        super.onStop()
    }

    override fun onDestroyView() {
        titleView?.animate()?.cancel()
        backButton = null
        titleView = null
        buildInfoRow = null
        actionSlot = null
        searchField = null
        clearButton = null
        resultsScroll = null
        resultsList = null
        contentContainer = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (consumeRestartNeeded()) {
            offerRestartPrompt(
                "Per applicare le modifiche è necessario riavviare l'app.\nVuoi riavviarla adesso?",
            )
        }
        super.onDismiss(dialog)
    }
}
