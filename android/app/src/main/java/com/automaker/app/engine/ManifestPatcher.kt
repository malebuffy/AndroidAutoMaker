package com.automaker.app.engine

import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType

object ManifestPatcher {
    const val AUTOMOTIVE_FEATURE = "android.hardware.type.automotive"
    const val GAMEPAD_FEATURE = "android.hardware.gamepad"
    const val LAUNCHER = "android.intent.category.LAUNCHER"
    const val CAR_LAUNCHER = "android.intent.category.CAR_LAUNCHER"
    const val MAIN = "android.intent.action.MAIN"
    const val DERIVED_APK_ID = "com.android.vending.derived.apk.id"
    const val DISTRACTION = "distractionOptimized"

    private const val ID_APP_CATEGORY = 0x01010545
    private const val ID_IS_GAME = 0x010103f4
    private const val ID_REQUIRED = 0x0101028e
    private val CATEGORIES = arrayOf(
        "game",
        "audio",
        "video",
        "image",
        "social",
        "news",
        "maps",
        "productivity",
        "accessibility",
    )

    fun patch(manifest: AndroidManifestBlock): List<String> {
        val changes = ArrayList<String>()
        val application = manifest.applicationElement
            ?: throw IllegalStateException("AndroidManifest.xml has no <application> element.")
        removeMeta(manifest, DERIVED_APK_ID, changes, "Removed the Play-derived APK id so the resigned package can be installed.")
        if (removeMeta(manifest, DISTRACTION, changes, null) || removeDistractionAttributes(manifest)) {
            changes.add("Removed distraction-optimized flags so Android Auto keeps the app in the parked category.")
        }
        declareGame(application, changes)
        ensureAutomotiveFeature(manifest, changes)
        ensureGamepadFeature(manifest, changes)
        ensureCarLauncher(manifest, changes)
        ensureExported(manifest, changes)
        manifest.fixNamespaces()
        manifest.fixAttributeNames()
        return listOf("Prepared as a parked game.")
    }

    private fun declareGame(application: ResXmlElement, changes: MutableList<String>) {
        val previous = findAttr(application, ID_APP_CATEGORY, "appCategory")
        val previousGame = findAttr(application, ID_IS_GAME, "isGame")
        val previousName = categoryName(previous)
        val already = previousName == "game" && previousGame != null && isTrue(previousGame)
        val category = application.getOrCreateAndroidAttribute("appCategory", ID_APP_CATEGORY)
        category.setTypeAndData(ValueType.DEC, 0)
        val game = application.getOrCreateAndroidAttribute("isGame", ID_IS_GAME)
        game.setValueAsBoolean(true)
        if (already) {
            changes.add("The app was already declared as a game.")
        } else if (previousName != null && previousName != "game") {
            changes.add("Changed the app category from $previousName to game so Android Auto can show it while parked.")
        } else {
            changes.add("Declared the app as a game so Android Auto can show its screen while parked.")
        }
    }

    private fun ensureAutomotiveFeature(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        ensureOptionalFeature(
            manifest,
            AUTOMOTIVE_FEATURE,
            changes,
            already = "Automotive hardware was already optional, so the APK still installs on a phone.",
            marked = "Marked automotive hardware as optional so the APK still installs on a phone.",
            added = "Declared optional automotive hardware so the same APK installs on a phone and in the car.",
        )
    }

    private fun ensureGamepadFeature(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        ensureOptionalFeature(
            manifest,
            GAMEPAD_FEATURE,
            changes,
            already = "Game controller support was already declared.",
            marked = "Marked game controller support as optional.",
            added = "Added game controller support.",
        )
    }

    private fun ensureOptionalFeature(
        manifest: AndroidManifestBlock,
        featureName: String,
        changes: MutableList<String>,
        already: String,
        marked: String,
        added: String,
    ) {
        val root = manifest.manifestElement
            ?: throw IllegalStateException("AndroidManifest.xml has no <manifest> element.")
        val existing = elementsNamed(root, AndroidManifest.TAG_uses_feature).firstOrNull { element ->
            AndroidManifestBlock.getAndroidNameValue(element) == featureName
        }
        if (existing != null) {
            val required = findAttr(existing, ID_REQUIRED, "required")
            if (required != null && isFalse(required)) {
                changes.add(already)
                return
            }
            existing.getOrCreateAndroidAttribute("required", ID_REQUIRED).setValueAsBoolean(false)
            changes.add(marked)
            return
        }
        val application = root.getElement(AndroidManifest.TAG_application)
        val feature = if (application != null) {
            root.newElementAt(application.index, AndroidManifest.TAG_uses_feature)
        } else {
            root.newElement(AndroidManifest.TAG_uses_feature)
        }
        feature.getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(featureName)
        feature.getOrCreateAndroidAttribute("required", ID_REQUIRED).setValueAsBoolean(false)
        changes.add(added)
    }

    private fun ensureCarLauncher(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        var added = 0
        for (activity in activities(manifest)) {
            for (filter in elementsNamed(activity, AndroidManifest.TAG_intent_filter)) {
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, LAUNCHER)) continue
                if (!hasNamedChild(filter, AndroidManifest.TAG_category, CAR_LAUNCHER)) {
                    filter.newElement(AndroidManifest.TAG_category)
                        .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                        .setValueAsString(CAR_LAUNCHER)
                    added += 1
                }
            }
        }
        if (added > 0) {
            changes.add(
                if (added == 1) {
                    "Added the Android Auto car launcher category to the main activity."
                } else {
                    "Added the Android Auto car launcher category to $added activities."
                },
            )
        } else if (activities(manifest).any { activityContains(it, CAR_LAUNCHER) }) {
            changes.add("The Android Auto car launcher category was already present.")
        } else {
            val first = activities(manifest).firstOrNull()
                ?: throw IllegalStateException("AndroidManifest.xml has no activity Android Auto can open.")
            val filter = first.newElement(AndroidManifest.TAG_intent_filter)
            filter.newElement(AndroidManifest.TAG_action)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(MAIN)
            filter.newElement(AndroidManifest.TAG_category)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(LAUNCHER)
            filter.newElement(AndroidManifest.TAG_category)
                .getOrCreateAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString(CAR_LAUNCHER)
            changes.add("Added an Android Auto car launcher filter to the first activity.")
        }
    }

    private fun ensureExported(manifest: AndroidManifestBlock, changes: MutableList<String>) {
        var changed = false
        for (activity in activities(manifest)) {
            if (!activityContains(activity, CAR_LAUNCHER)) continue
            val exported = findAttr(activity, AndroidManifest.ID_exported, AndroidManifest.NAME_exported)
            if (exported != null && isTrue(exported)) continue
            activity.getOrCreateAndroidAttribute(AndroidManifest.NAME_exported, AndroidManifest.ID_exported)
                .setValueAsBoolean(true)
            changed = true
        }
        if (changed) changes.add("Marked the car activity exported so Android Auto can start it.")
    }

    private fun removeMeta(
        manifest: AndroidManifestBlock,
        name: String,
        changes: MutableList<String>,
        message: String?,
    ): Boolean {
        val found = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            if (element.equalsName(AndroidManifest.TAG_meta_data) && AndroidManifestBlock.getAndroidNameValue(element) == name) {
                found.add(element)
            }
        }
        for (element in found) {
            element.parentElement?.removeElementsIf { it === element }
        }
        if (found.isNotEmpty() && message != null) changes.add(message)
        return found.isNotEmpty()
    }

    private fun removeDistractionAttributes(manifest: AndroidManifestBlock): Boolean {
        var removed = false
        val elements = ArrayList<ResXmlElement>()
        val iterator = manifest.recursiveElements()
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            elements.add(element)
        }
        for (element in elements) {
            val attribute = element.searchAttributeByName(DISTRACTION) ?: continue
            if (!isTrue(attribute)) continue
            element.removeAttribute(attribute)
            removed = true
        }
        return removed
    }

    private fun activities(manifest: AndroidManifestBlock): List<ResXmlElement> {
        val list = ArrayList<ResXmlElement>()
        val iterator = manifest.getActivities(true)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            list.add(element)
        }
        return list
    }

    private fun elementsNamed(parent: ResXmlElement, name: String): List<ResXmlElement> {
        val list = ArrayList<ResXmlElement>()
        val iterator = parent.getElements(name)
        while (iterator.hasNext()) {
            val element = iterator.next() as? ResXmlElement ?: continue
            list.add(element)
        }
        return list
    }

    private fun hasNamedChild(parent: ResXmlElement, tag: String, value: String): Boolean {
        return elementsNamed(parent, tag).any { AndroidManifestBlock.getAndroidNameValue(it) == value }
    }

    private fun activityContains(activity: ResXmlElement, value: String): Boolean {
        return elementsNamed(activity, AndroidManifest.TAG_intent_filter).any { filter ->
            hasNamedChild(filter, AndroidManifest.TAG_category, value)
        }
    }

    private fun findAttr(element: ResXmlElement, id: Int, name: String) =
        element.searchAttributeByResourceId(id) ?: element.searchAttributeByName(name)

    private fun categoryName(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute?): String? {
        if (attribute == null) return null
        if (attribute.valueType == ValueType.STRING) return attribute.valueAsString
        val index = attribute.data
        return CATEGORIES.getOrNull(index)
    }

    private fun isTrue(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute): Boolean {
        return when (attribute.valueType) {
            ValueType.BOOLEAN -> attribute.valueAsBoolean
            ValueType.DEC, ValueType.HEX -> attribute.data != 0
            else -> attribute.valueAsString == "true"
        }
    }

    private fun isFalse(attribute: com.reandroid.arsc.chunk.xml.ResXmlAttribute): Boolean {
        return when (attribute.valueType) {
            ValueType.BOOLEAN -> !attribute.valueAsBoolean
            ValueType.DEC, ValueType.HEX -> attribute.data == 0
            else -> attribute.valueAsString == "false"
        }
    }
}
