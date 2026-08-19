import { useEffect, useRef } from "react";
import { Button } from "./ui";

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  destructive?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}

export function ConfirmDialog(props: ConfirmDialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (props.open && !dialog.open) {
      opener.current = document.activeElement as HTMLElement;
      dialog.showModal();
    } else if (!props.open && dialog.open) dialog.close();
  }, [props.open]);
  const close = () => {
    props.onClose();
    requestAnimationFrame(() => opener.current?.focus());
  };
  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby="confirm-title"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onClose={() => props.open && props.onClose()}
    >
      <h2 id="confirm-title">{props.title}</h2>
      <p>{props.description}</p>
      <div className="dialogActions">
        <Button type="button" className="secondary" onClick={close} disabled={props.busy}>
          Cancel
        </Button>
        <Button
          type="button"
          className={props.destructive ? "danger" : ""}
          onClick={props.onConfirm}
          disabled={props.busy}
        >
          {props.busy ? "Working…" : (props.confirmLabel ?? "Confirm")}
        </Button>
      </div>
    </dialog>
  );
}
