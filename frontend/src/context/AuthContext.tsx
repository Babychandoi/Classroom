import React, { createContext, useContext, useState, useEffect } from 'react';
import { User, AuthResponse } from '../types';
import { api, setAccessToken, setSessionExpiredHandler } from '../api/client';

interface AuthContextType {
  user: User | null;
  token: string | null;
  isLoading: boolean;
  login: (email: string, pass: string) => Promise<void>;
  register: (email: string, pass: string, name: string) => Promise<void>;
  quickLogin: (email: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(false);

  const refreshUser = async () => {
    try {
      const u = await api.get<User>('/me');
      setUser(u);
    } catch {
      setUser(null);
      setAccessToken(null);
      setToken(null);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    setSessionExpiredHandler(() => { setToken(null); setUser(null); });
    return () => setSessionExpiredHandler(null);
  }, []);

  const login = async (email: string, pass: string) => {
    const res = await api.post<AuthResponse>('/auth/login', { email, password: pass });
    setAccessToken(res.token);
    setToken(res.token);
    await refreshUser();
  };

  const register = async (email: string, pass: string, name: string) => {
    const res = await api.post<AuthResponse>('/auth/register', { email, password: pass, fullName: name });
    setAccessToken(res.token);
    setToken(res.token);
    await refreshUser();
  };

  const quickLogin = async (email: string) => {
    if (import.meta.env.VITE_ENABLE_DEMO_LOGIN !== 'true') {
      throw new Error('Đăng nhập demo không được bật trong môi trường này');
    }
    await login(email, 'Password123!');
  };

  const logout = async () => {
    const previousToken = token;
    setAccessToken(null);
    setToken(null);
    setUser(null);
    try {
      // Revocation requires the previous credential. The UI has already cleared it.
      if (previousToken) await api.post('/auth/logout', undefined, { headers: { Authorization: `Bearer ${previousToken}` } });
    } catch { /* Session already invalid or network unavailable. */ }
  };

  return (
    <AuthContext.Provider value={{ user, token, isLoading, login, register, quickLogin, logout, refreshUser }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
