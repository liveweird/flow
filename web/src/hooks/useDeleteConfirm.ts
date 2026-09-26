import { useState } from "react";
import { useDisclosure } from "@mantine/hooks";
import { useMutation, type UseMutationResult } from "@tanstack/react-query";
import { showSuccessToast } from "../utils/toast";

export type DeleteConfirm<T> = {
  target: T | null;
  opened: boolean;
  requestDelete: (row: T) => void;
  cancelDelete: () => void;
  confirmDelete: () => void;
  mutation: UseMutationResult<unknown, Error, T>;
};

/**
 * The shared request→confirm→mutate flow behind every ConfirmDeleteModal: the hook owns the
 * modal state and the success toast; the page owns query invalidation (via onSuccess).
 */
export function useDeleteConfirm<T>({
  mutationFn,
  onSuccess,
  successMessage,
}: {
  mutationFn: (row: T) => Promise<unknown>;
  onSuccess: (row: T) => Promise<unknown> | void;
  successMessage?: string;
}): DeleteConfirm<T> {
  const [target, setTarget] = useState<T | null>(null);
  const [opened, { open, close }] = useDisclosure(false);

  const mutation = useMutation({
    mutationFn,
    onSuccess: async (_result: unknown, row: T) => {
      close();
      setTarget(null);
      if (successMessage) showSuccessToast(successMessage);
      await onSuccess(row);
    },
  });

  function requestDelete(row: T) {
    setTarget(row);
    mutation.reset();
    open();
  }
  function cancelDelete() {
    if (mutation.isPending) return;
    close();
    setTarget(null);
    mutation.reset();
  }
  function confirmDelete() {
    if (target) mutation.mutate(target);
  }

  return { target, opened, requestDelete, cancelDelete, confirmDelete, mutation };
}
