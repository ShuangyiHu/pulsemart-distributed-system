import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import client from '../api/client';
import './OrdersListPage.css';

const STATUS_COLORS = {
  PENDING: '#f59e0b',
  INVENTORY_RESERVED: '#3b82f6',
  PAYMENT_PENDING: '#8b5cf6',
  COMPLETED: '#10b981',
  CANCELLED: '#ef4444',
};

export default function OrdersListPage() {
  const { customerId } = useAuth();
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(true);

  const fetchOrders = async () => {
    try {
      const res = await client.get(`/orders?customerId=${customerId}`);
      setOrders(res.data);
    } catch (err) {
      console.error('Failed to fetch orders', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchOrders();
    const interval = setInterval(fetchOrders, 5000);
    return () => clearInterval(interval);
  }, [customerId]);

  if (loading) return <div className="page"><p>Loading orders...</p></div>;

  return (
    <div className="page">
      <div className="orders-header">
        <h2>My Orders</h2>
        <Link to="/place-order" className="btn btn-primary">+ New Order</Link>
      </div>

      {orders.length === 0 ? (
        <div className="card">
          <p className="text-muted">No orders yet. Place your first order!</p>
        </div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Order ID</th>
              <th>Status</th>
              <th>Total</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((order) => (
              <tr key={order.orderId}>
                <td>
                  <Link to={`/orders/${order.orderId}`}>
                    {order.orderId.substring(0, 8)}...
                  </Link>
                </td>
                <td>
                  <span
                    className="badge"
                    style={{ backgroundColor: STATUS_COLORS[order.status] || '#6b7280' }}
                  >
                    {order.status}
                  </span>
                </td>
                <td>${parseFloat(order.totalAmount).toFixed(2)}</td>
                <td>{new Date(order.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
