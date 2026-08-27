/**
 * No-op shim for MobileDashboard components.
 * Settings pages import ConfirmationModal, MemberProfileEditor, etc.
 * from MobileDashboard — these pages are filtered out in kiosk mode
 * so the imports never execute, but they must resolve at bundle time.
 */

export class ConfirmationModal {
  constructor() {}
  show() { return Promise.resolve(false); }
}

export function showTextInputModal() { return Promise.resolve(null); }
export function showAddMemberModal() { return Promise.resolve(null); }
export class MemberProfileEditor {}
