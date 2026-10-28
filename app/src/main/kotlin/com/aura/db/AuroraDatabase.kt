package com.aura.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// Entities and DAOs live in per-aggregate files in this package:
//   Contacts.kt  (ContactEntity, PairState, ContactDao)
//   Messages.kt  (MessageEntity, UnreadByContact, MessageDao)
//   Ratchet.kt   (KemRatchetEntity, RatchetDao)
//   Prekeys.kt   (PrekeyEntity, PrekeyDao)
//   MeshPeers.kt (MeshPeerEntity, MeshPeerDao)

@Database(
    entities = [
        ContactEntity::class, MessageEntity::class, MeshPeerEntity::class,
        PrekeyEntity::class, KemRatchetEntity::class,
        GroupEntity::class, GroupMemberEntity::class, GroupMembershipEventEntity::class,
        GroupMessageEntity::class, GroupDeliveryEntity::class
    ],
    version = 12,
    // Export the schema so version 6 became the migration baseline: from here on,
    // changes ship as @AutoMigration / Migration objects that PRESERVE user data
    // instead of wiping it. (Schema JSONs land in app/schemas — commit them.)
    // v6→v7 adds the `prekeys` table (forward-secret PQXDH handshake); a purely
    // additive change, so an automatic migration carries every real user across.
    // v7→v8 (crypto re-engineering): the prekey KEM moves to X-Wing, collapsing the
    // kyber/x25519 column pairs to single kem* columns. Prekeys are ephemeral and
    // regenerated on next publish, so MIGRATION_7_8 just recreates the table —
    // contacts and messages are untouched. (Old pairings are cryptographically
    // invalidated by the identity-format change and re-paired at the app layer.)
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 6, to = 7)]
)
abstract class AuroraDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun meshPeerDao(): MeshPeerDao
    abstract fun ratchetDao(): RatchetDao
    abstract fun prekeyDao(): PrekeyDao
    abstract fun groupDao(): GroupDao
    abstract fun groupMessageDao(): GroupMessageDao

    companion object {
        /** Build the SQLCipher-encrypted database. [dbKey] must be 32 bytes. */
        fun build(context: Context, dbKey: ByteArray): AuroraDatabase {
            require(dbKey.size == 32) { "DB key must be 32 bytes" }
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AuroraDatabase::class.java, "aura.db")
                .openHelperFactory(SupportOpenHelperFactory(dbKey))
                // Only the pre-export schemas (v1..v5, which have no committed schema to
                // migrate from) are allowed to wipe. From v6 onward every bump MUST ship a
                // migration, so a real user's contacts/messages survive app updates.
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .build()
        }

        /**
         * v11→v12 (group messaging delivery, additive): add `group_messages` (one logical message per
         * row) and `group_deliveries` (per-recipient fan-out jobs). Additive — all prior data untouched.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_messages` (" +
                        "`messageId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `senderId` TEXT NOT NULL, " +
                        "`fromMe` INTEGER NOT NULL, `body` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, `senderSeq` INTEGER NOT NULL, `logicalClock` INTEGER NOT NULL, " +
                        "`membershipVersion` INTEGER NOT NULL, `localState` TEXT NOT NULL, " +
                        "`replyToId` TEXT, `expiresAtMs` INTEGER, PRIMARY KEY(`messageId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_deliveries` (" +
                        "`messageId` TEXT NOT NULL, `recipientId` TEXT NOT NULL, `groupId` TEXT NOT NULL, " +
                        "`sealedB64` TEXT, `ratchetN` INTEGER, `state` TEXT NOT NULL, `retryCount` INTEGER NOT NULL, " +
                        "`nextRetryMs` INTEGER NOT NULL, `lastAttemptMs` INTEGER NOT NULL, `ackState` TEXT NOT NULL, " +
                        "PRIMARY KEY(`messageId`, `recipientId`))"
                )
            }
        }

        /**
         * v10→v11 (group messaging, additive): add the `groups`, `group_members` and
         * `group_membership_events` tables. Purely additive — contacts, messages, ratchets and
         * prekeys are untouched, so every existing 1:1 user carries across unchanged.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `groups` (" +
                        "`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, `creatorId` TEXT NOT NULL, " +
                        "`creatorVerifyKeyB64` TEXT NOT NULL, `membershipVersion` INTEGER NOT NULL, " +
                        "`stateHash` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `role` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, PRIMARY KEY(`groupId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_members` (" +
                        "`groupId` TEXT NOT NULL, `memberId` TEXT NOT NULL, `verifyKeyB64` TEXT NOT NULL, " +
                        "`kemPubB64` TEXT NOT NULL, `addedAtVersion` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `memberId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_membership_events` (" +
                        "`eventId` TEXT NOT NULL, `groupId` TEXT NOT NULL, `version` INTEGER NOT NULL, " +
                        "`prevStateHash` TEXT NOT NULL, `action` TEXT NOT NULL, `affectedMemberId` TEXT NOT NULL, " +
                        "`affectedVerifyKeyB64` TEXT NOT NULL, `affectedKemPubB64` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `creatorId` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                        "`signatureB64` TEXT NOT NULL, PRIMARY KEY(`eventId`))"
                )
            }
        }

        /**
         * v9→v10: retire the symmetric ratchet. Its `ratchet_state` (chains + SAS fingerprint +
         * media key) and `ratchet_skipped` tables are dropped; the single `KemDoubleRatchet`
         * (`kem_ratchet`) now carries the SAS fingerprint and media key in its own blob.
         *
         * The `kem_ratchet` blob format also changed incompatibly (it gained a versioned
         * `[1B ver][8B sasFp][32B mediaKey]` header). A v8/v9 row holds the *old* bare
         * `sessionToBytes` blob with no header and — crucially — no media key (that lived in the
         * now-dropped `ratchet_state`), so it can't be salvaged. We **clear** `kem_ratchet` here so
         * no stale unversioned blob ever reaches the new parser; those contacts re-pair (the same
         * clean-break / re-pair contract `StartupMigrations` applies on the pre-FIPS upgrade).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `ratchet_state`")
                db.execSQL("DROP TABLE IF EXISTS `ratchet_skipped`")
                db.execSQL("DELETE FROM `kem_ratchet`")
            }
        }

        /**
         * v7→v8: rebuild `prekeys` with X-Wing columns (kemPubB64/kemPrivB64) replacing the
         * old kyber/x25519 pairs. Prekeys regenerate on next publish, so dropping them is
         * lossless for the user; contacts and messages are left intact.
         */
        /** v8→v9 (Phase 5): add the `kem_ratchet` table holding the post-quantum KEM Double
         *  Ratchet session blob per contact. Additive; existing data untouched. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `kem_ratchet` (" +
                        "`contactNodeIdHex` TEXT NOT NULL, `sessionB64` TEXT NOT NULL, " +
                        "PRIMARY KEY(`contactNodeIdHex`))"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `prekeys`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `prekeys` (" +
                        "`prekeyId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`kemPubB64` TEXT NOT NULL, `kemPrivB64` TEXT NOT NULL, " +
                        "`createdAtMs` INTEGER NOT NULL, `usedAtMs` INTEGER, " +
                        "PRIMARY KEY(`prekeyId`))"
                )
            }
        }
    }
}
