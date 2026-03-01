import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import Navbar from './components/Navbar';
import ProtectedRoute from './components/ProtectedRoute';
import LoginPage from './pages/LoginPage';
import PlaceOrderPage from './pages/PlaceOrderPage';
import OrdersListPage from './pages/OrdersListPage';
import OrderDetailPage from './pages/OrderDetailPage';
import './App.css';

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Navbar />
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/orders" element={<ProtectedRoute><OrdersListPage /></ProtectedRoute>} />
          <Route path="/orders/:orderId" element={<ProtectedRoute><OrderDetailPage /></ProtectedRoute>} />
          <Route path="/place-order" element={<ProtectedRoute><PlaceOrderPage /></ProtectedRoute>} />
          <Route path="*" element={<Navigate to="/orders" />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;
