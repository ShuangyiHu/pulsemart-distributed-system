import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import './Navbar.css';

export default function Navbar() {
  const { token, customerId, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <nav className="navbar">
      <div className="navbar-brand">
        <Link to="/">PulseMart</Link>
      </div>
      {token && (
        <div className="navbar-links">
          <Link to="/place-order">Place Order</Link>
          <Link to="/orders">My Orders</Link>
          <span className="navbar-user">Customer: {customerId?.substring(0, 8)}...</span>
          <button onClick={handleLogout} className="btn btn-small">Logout</button>
        </div>
      )}
    </nav>
  );
}
