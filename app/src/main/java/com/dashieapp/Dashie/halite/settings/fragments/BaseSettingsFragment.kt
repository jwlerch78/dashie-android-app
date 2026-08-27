package com.dashieapp.Dashie.halite.settings.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.SettingsProfiler
import com.dashieapp.Dashie.halite.settings.SettingsTrace
import com.dashieapp.Dashie.halite.settings.adapters.SettingsAdapter
import com.dashieapp.Dashie.halite.settings.items.SettingsItem

/**
 * Base fragment for settings screens.
 * Provides common functionality for nav bar, recycler view, and navigation.
 */
abstract class BaseSettingsFragment : Fragment() {

    protected lateinit var recyclerView: RecyclerView
    protected lateinit var adapter: SettingsAdapter

    /** Title to display in the nav bar */
    abstract val title: String

    /** Whether to show the back button (false for root screen) */
    open val showBackButton: Boolean = true

    /**
     * Whether to show the nav-bar "Done" button (closes all of Settings).
     * Overridden to false on data-entry forms where "Done" would discard
     * the form without saving — D.30.
     */
    open val showDoneButton: Boolean = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val token = SettingsProfiler.start("Fragment.onViewCreated($title)")
        SettingsTrace.shown("Fragment/${this::class.simpleName} title='$title'")
        super.onViewCreated(view, savedInstanceState)

        setupNavBar(view)
        setupRecyclerView(view)
        // Defer content loading so the nav bar + empty card render first.
        view.post { loadItems() }
        SettingsProfiler.end(token)
    }

    private fun setupNavBar(view: View) {
        // Set title
        view.findViewById<TextView>(R.id.tvTitle).text = title

        // Back button
        val btnBack = view.findViewById<ImageButton>(R.id.btnBack)
        if (showBackButton) {
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { navigateBack() }
        } else {
            btnBack.visibility = View.GONE
        }

        // Done button — close settings and re-open Control Center.
        // Hidden on data-entry forms (showDoneButton = false) where it would
        // discard the form without saving (D.30).
        val btnClose = view.findViewById<TextView>(R.id.btnClose)
        if (showDoneButton) {
            btnClose.visibility = View.VISIBLE
            btnClose.setOnClickListener {
                (requireActivity() as? SettingsActivity)?.finishWithReopenCC()
            }
        } else {
            btnClose.visibility = View.GONE
        }
    }

    private fun setupRecyclerView(view: View) {
        recyclerView = view.findViewById(R.id.settingsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // Note: shared ViewHolder pool disabled — caused click handlers from previous
        // fragments to handle clicks on replaced fragments during back-stack transitions.
        // Each fragment now has its own pool, which is slightly slower but correct.
        // (requireActivity() as? SettingsActivity)?.let {
        //     recyclerView.setRecycledViewPool(it.sharedViewPool)
        // }

        adapter = SettingsAdapter(
            onItemClick = { item -> handleItemClick(item) },
            onToggleChange = { item, newValue -> handleToggleChange(item, newValue) },
            onSliderChange = { item, newValue -> handleSliderChange(item, newValue) },
            onTextChange = { item, newValue -> handleTextChange(item, newValue) }
        )
        recyclerView.adapter = adapter
    }

    /**
     * Load the list of settings items to display.
     * Subclasses must implement this to provide their items.
     */
    protected abstract fun getItems(): List<SettingsItem>

    /**
     * Reload items and update the adapter.
     */
    protected fun loadItems() {
        val items = SettingsProfiler.measure("Fragment.getItems($title)") { getItems() }
        SettingsProfiler.log("  → $title: ${items.size} items")
        SettingsProfiler.measure("Fragment.submitList($title, ${items.size} items)") {
            adapter.submitList(items)
        }
    }

    /**
     * Refresh a specific item in the list.
     */
    protected fun refreshItems() {
        loadItems()
    }

    /**
     * Force a rebind of all visible items — use when the underlying model values
     * haven't changed but the VIEW state has drifted and needs to snap back.
     *
     * Specifically: when a user taps a Switch, the adapter optimistically flips the
     * Switch's visual state before the toggle interceptor fires. If the interceptor
     * then cancels (e.g. user dismisses a confirmation dialog), `submitList` with
     * unchanged items leaves DiffUtil seeing no diff — so the Switch stays visually
     * flipped even though the underlying pref is untouched. notifyDataSetChanged
     * forces every visible ViewHolder to re-bind from the model, snapping the
     * Switch back to its real state.
     */
    @Suppress("NotifyDataSetChanged")
    protected fun forceRebindAllItems() {
        loadItems()
        adapter.notifyDataSetChanged()
    }

    /**
     * Handle click on a settings item.
     * Subclasses should override this to handle navigation and actions.
     */
    protected open fun handleItemClick(item: SettingsItem) {
        // Default: no action
    }

    /**
     * Handle toggle state change.
     * Subclasses should override this to persist toggle changes.
     */
    protected open fun handleToggleChange(item: SettingsItem.Toggle, newValue: Boolean) {
        // Default: no action
    }

    /**
     * Handle text input change.
     * Subclasses should override this to handle text field updates.
     */
    protected open fun handleTextChange(item: SettingsItem.TextInput, newValue: String) {
        // Default: no action
    }

    /**
     * Handle slider value change.
     * Subclasses should override this to persist slider changes.
     */
    protected open fun handleSliderChange(item: SettingsItem.Slider, newValue: Int) {
        // Default: no action
    }

    /**
     * Navigate to a child fragment.
     */
    protected fun navigateTo(fragment: Fragment, tag: String) {
        (requireActivity() as? SettingsActivity)?.showFragment(fragment, tag)
    }

    /**
     * Navigate back to the previous screen.
     * If at root, finishes with RESULT_REOPEN_CC so the Control Center re-opens.
     * Public so the activity can delegate hardware back presses to the fragment.
     */
    open fun navigateBack() {
        if (!isAdded) return
        val activity = activity as? SettingsActivity ?: return
        if (!activity.popBackStackDirect()) {
            activity.finishWithReopenCC()
        }
    }

    /**
     * Get the settings activity for accessing shared resources.
     */
    protected val settingsActivity: SettingsActivity?
        get() = activity as? SettingsActivity
}
