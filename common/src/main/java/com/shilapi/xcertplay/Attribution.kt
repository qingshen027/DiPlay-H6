package com.shilapi.xcertplay

/**
 * Attribution for this Haval H6 build.
 *
 * These values live in compiled code rather than in `strings.xml` on purpose: a resource is the
 * easiest thing in an APK to edit, while this text is the one thing that must survive
 * redistribution. GPL-3.0 section 7(b) lets a distributor require that author attribution be
 * preserved, and section 7(d) lets it restrict use of the author's name for publicity, so keeping
 * the wording in code (and asserting it in `AttributionIntegrityTest`) is the practical way to
 * make that stick.
 *
 * Nothing here blocks any GPL-granted right: redistribution is still allowed. It only requires
 * that the credit travels with the copy.
 */
object Attribution {
    /** Marketing label for this adaptation, shown next to the upstream version. */
    const val RELEASE_LABEL = "V3.0"

    /** Who adapted this build; must be displayed intact wherever the build is described. */
    const val ADAPTED_BY = "适配 by 情深"

    /** The car this build is adapted for. */
    const val TARGET_CAR = "哈弗 H6 国潮版"

    /** Name whose use for publicity is restricted under GPL-3.0 section 7(d). */
    const val ADAPTER_NAME = "情深"

    /** Asset holding the full attribution and licence text rendered in About. */
    const val NOTICE_ASSET = "NOTICE.txt"

    /** Bumped whenever the disclaimer wording changes, to re-prompt existing users. */
    const val DISCLAIMER_VERSION = 1
}
