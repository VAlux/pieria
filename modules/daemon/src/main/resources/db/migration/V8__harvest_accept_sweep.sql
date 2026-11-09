-- Set when the user accepts a sweep the mass-delete guard held back. Applies to the next sweep only.
ALTER TABLE harvest_sources ADD COLUMN accept_next_sweep INTEGER NOT NULL DEFAULT 0;
