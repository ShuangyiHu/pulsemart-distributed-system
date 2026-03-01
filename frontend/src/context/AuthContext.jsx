import { createContext, useContext, useState } from 'react';
import client from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [token, setToken] = useState(localStorage.getItem('token'));
  const [customerId, setCustomerId] = useState(localStorage.getItem('customerId'));

  const login = async (userId, custId) => {
    const res = await client.post('/auth/token', { userId, customerId: custId });
    const jwt = res.data.token;
    localStorage.setItem('token', jwt);
    localStorage.setItem('customerId', custId);
    setToken(jwt);
    setCustomerId(custId);
  };

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('customerId');
    setToken(null);
    setCustomerId(null);
  };

  return (
    <AuthContext.Provider value={{ token, customerId, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
