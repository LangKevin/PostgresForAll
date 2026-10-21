package com.postgresforall.core.model;

/**
 * One generated DDL statement, with what it locks and whether it may run in a
 * transaction (CREATE / DROP / REINDEX ... CONCURRENTLY may not).
 */
public record DdlStatement(String sql, boolean transactional, Lock lock, String lockedTable) {

    public DdlStatement(String sql, Lock lock, String lockedTable) {
        this(sql, true, lock, lockedTable);
    }

    /** PostgreSQL lock levels relevant to DDL, with what each means for other sessions. */
    public enum Lock {
        ACCESS_EXCLUSIVE("ACCESS EXCLUSIVE", "blocks all reads and writes"),
        EXCLUSIVE("EXCLUSIVE", "blocks writes and most reads that lock"),
        SHARE_ROW_EXCLUSIVE("SHARE ROW EXCLUSIVE", "blocks writes"),
        SHARE("SHARE", "blocks writes"),
        SHARE_UPDATE_EXCLUSIVE("SHARE UPDATE EXCLUSIVE", "allows reads and writes"),
        NONE("no table lock", "no blocking");

        public final String label;
        public final String effect;

        Lock(String label, String effect) {
            this.label = label;
            this.effect = effect;
        }
    }

    /** "SHARE lock on public.orders — blocks writes". */
    public String lockDescription() {
        if (lock == Lock.NONE || lockedTable == null) return "No table lock";
        return lock.label + " lock on " + lockedTable + " — " + lock.effect;
    }
}
