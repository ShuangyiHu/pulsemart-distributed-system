import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import client from '../api/client';

const STATUS_COLORS = {
  PENDING: '#f59e0b',
  INVENTORY_RESERVED: '#3b82f6',
  PAYMENT_PENDING: '#8b5cf6',
  COMPLETED: '#10b981',
  CANCELLED: '#ef4444',
};

const TERMINAL_STATUSES = ['COMPLETED', 'CANCELLED'];

export default function OrderDetailPage() {
  const { orderId } = useParams();
  const [order, setOrder] = useState(null);
  const [summary, setSummary] = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(true);
  const [error, setError] = useState('');

  const fetchOrder = async () => {
    try {
      const res = await client.get(`/orders/${orderId}`);
      setOrder(res.data);
      return res.data;
    } catch (err) {
      setError('Failed to load order');
      return null;
    }
  };

  const fetchSummary = async () => {
    try {
      const res = await client.get(`/summaries/${orderId}`);
      setSummary(res.data);
      setSummaryLoading(false);
    } catch {
      setSummaryLoading(false);
    }
  };

  useEffect(() => {
    fetchOrder();
    fetchSummary();
  }, [orderId]);

  // Auto-refresh if not terminal
  useEffect(() => {
    if (order && TERMINAL_STATUSES.includes(order.status)) {
      // One more summary fetch in case it wasn't ready
      if (!summary) {
        const interval = setInterval(async () => {
          await fetchSummary();
        }, 3000);
        return () => clearInterval(interval);
      }
      return;
    }

    const interval = setInterval(async () => {
      await fetchOrder();
      await fetchSummary();
    }, 2000);
    return () => clearInterval(interval);
  }, [order?.status, summary]);

  if (error) return <div className="page"><p className="error">{error}</p></div>;
  if (!order) return <div className="page"><p>Loading...</p></div>;

  return (
    <div className="page">
      <Link to="/orders" className="back-link">Back to Orders</Link>
      <h2>Order Detail</h2>

      <div className="card">
        <div className="detail-grid">
          <div>
            <span className="label">Order ID</span>
            <span className="value">{order.orderId}</span>
          </div>
          <div>
            <span className="label">Status</span>
            <span
              className="badge"
              style={{ backgroundColor: STATUS_COLORS[order.status] || '#6b7280' }}
            >
              {order.status}
            </span>
          </div>
          <div>
            <span className="label">Total</span>
            <span className="value">${parseFloat(order.totalAmount).toFixed(2)}</span>
          </div>
          <div>
            <span className="label">Created</span>
            <span className="value">{new Date(order.createdAt).toLocaleString()}</span>
          </div>
        </div>
      </div>

      <h3>Items</h3>
      <table>
        <thead>
          <tr>
            <th>Product ID</th>
            <th>Quantity</th>
            <th>Unit Price</th>
            <th>Subtotal</th>
          </tr>
        </thead>
        <tbody>
          {order.items?.map((item, i) => (
            <tr key={i}>
              <td>{item.productId?.substring(0, 8)}...</td>
              <td>{item.quantity}</td>
              <td>${parseFloat(item.unitPrice).toFixed(2)}</td>
              <td>${(item.quantity * parseFloat(item.unitPrice)).toFixed(2)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h3>AI Summary</h3>
      <div className="card">
        {summary ? (
          <>
            <p>{summary.summaryText}</p>
            <div className="summary-meta">
              <span>Model: {summary.modelUsed}</span>
              <span>Tokens: {summary.promptTokens} in / {summary.completionTokens} out</span>
            </div>
          </>
        ) : summaryLoading || !TERMINAL_STATUSES.includes(order.status) ? (
          <p className="text-muted">
            <span className="spinner"></span> Generating summary...
          </p>
        ) : (
          <p className="text-muted">No summary available.</p>
        )}
      </div>
    </div>
  );
}
