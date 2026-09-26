import '@testing-library/jest-dom';

// Provide the same Web Storage contract under Node/jsdom versions that expose
// an incomplete localStorage global (notably Node's --localstorage-file shim).
const storageData = new Map<string, string>();
const storage: Storage = {
  get length() { return storageData.size; },
  clear: () => storageData.clear(),
  getItem: (key) => storageData.get(String(key)) ?? null,
  key: (index) => [...storageData.keys()][index] ?? null,
  removeItem: (key) => { storageData.delete(String(key)); },
  setItem: (key, value) => { storageData.set(String(key), String(value)); },
};
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: storage });
