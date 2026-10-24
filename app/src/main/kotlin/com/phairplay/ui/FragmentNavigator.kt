package com.phairplay.ui

import android.content.Context
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.phairplay.R

/**
 * FragmentNavigator — Owns the nav panel selection and the content fragment swap.
 *
 * WHAT: Keeps track of which nav panel item is selected, highlights it and replaces the
 * fragment in the content container when the selection changes.
 *
 * WHY: [com.phairplay.MainActivity] used to do the navigation itself, which mixed the
 * Activity's view setup with the fragment transactions and pushed the class past the
 * 400-line limit of CONTRIBUTING.md. The navigator keeps the nav panel and the content
 * area in sync in one place.
 *
 * HOW: The Activity creates one navigator with its fragment manager and its two nav
 * panel items, calls [setupNavigation] to install the click listeners and afterwards
 * calls [showHome] or [showSettings] to switch the content.
 *
 * Example:
 *   val navigator = FragmentNavigator(supportFragmentManager, this, navItemHome, navItemSettings)
 *   navigator.setupNavigation()
 *   navigator.showHome()
 */
internal class FragmentNavigator(
    /** Fragment manager of the hosting Activity — performs the transaction. */
    private val fragmentManager: FragmentManager,
    /** Context used to resolve the nav highlight colors. */
    private val context: Context,
    /** The "Home" nav panel item. */
    private val navItemHome: TextView,
    /** The "Settings" nav panel item. */
    private val navItemSettings: TextView
) {

    /**
     * Index of the currently selected nav item (0 = Home, 1 = Settings).
     *
     * The index keeps a click on the already selected item from re-creating the
     * fragment, which would throw away the user's scroll position for nothing.
     */
    var selectedNavIndex: Int = 0
        private set

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                showHome()
            }
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) {
                showSettings()
            }
        }

        // Set initial selected state
        setNavSelected(navItemHome, true)
        setNavSelected(navItemSettings, false)
    }

    /** Shows the [HomeFragment] in the content area. */
    fun showHome() = navigateTo(HomeFragment(), navItemHome)

    /** Shows the [SettingsFragment] in the content area. */
    fun showSettings() = navigateTo(SettingsFragment(), navItemSettings)

    /**
     * Replaces the content_container fragment with [fragment] and updates
     * the nav panel selection highlight.
     *
     * @param fragment  The Fragment to show in the content area.
     * @param navItem   The nav panel TextView that was clicked (for highlight update).
     */
    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        // Update nav highlight
        setNavSelected(navItemHome, navItem == navItemHome)
        setNavSelected(navItemSettings, navItem == navItemSettings)
        selectedNavIndex = if (navItem == navItemHome) 0 else 1

        // Replace fragment
        fragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /**
     * Updates the nav panel item's visual state.
     *
     * @param item     The nav item TextView.
     * @param selected True if this item is currently active.
     */
    private fun setNavSelected(item: TextView, selected: Boolean) {
        item.isSelected = selected
        item.setTextColor(
            context.getColor(if (selected) R.color.text_primary else R.color.nav_item_normal)
        )
    }
}
