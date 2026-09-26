interface StackRuntime {
  responds: (url: string) => Promise<boolean>;
  start: () => void;
  wait: (url: string) => Promise<void>;
}

/** Starting a service never implies ownership of its data. There is deliberately no teardown:
 * an existing database and even a partially started stack survive success, failure and retries. */
export async function ensureStack(url: string, runtime: StackRuntime): Promise<void> {
  if (await runtime.responds(url)) return;
  if (url !== "http://localhost:8084") {
    throw new Error(`E2E target ${url} is not ready; start that deployment before running tests`);
  }
  runtime.start();
  await runtime.wait(url);
}
