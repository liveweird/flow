package ch.nokillswit.ingest

/** The lease was reclaimed by another worker (or the job left RUNNING) — the run stops without touching cursors. */
class LeaseLostException(jobId: UInt) : Exception("Lease lost for sync job $jobId")

/** `cancel_requested_at` was set against this RUNNING job — the run stops cooperatively. */
class JobCancelRequestedException(jobId: UInt) : Exception("Cancel requested for sync job $jobId")
