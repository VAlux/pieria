// Shared, mutable console state. Modules import this object and read/write its fields directly.
export const state = {
  profile: "",
  view: "memories",
  memories: [],          // last fetched list for the current profile
  includeSuperseded: false,
  // The type filter is a segmented control rather than a <select>, so its value lives here
  // instead of on a form element.
  typeFilter: ""
};
