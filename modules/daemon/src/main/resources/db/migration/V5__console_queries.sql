-- Console list views page through memories and audit events by time. The memory list orders a
-- profile's rows by store time with the id as tie-breaker; the daemon-wide activity feed orders
-- every profile's audit rows by completion time.
CREATE INDEX idx_mem_profile_created ON memories (profile_id, created_at, id);
CREATE INDEX idx_audit_time ON profile_audit_events (completed_at DESC, id DESC);
