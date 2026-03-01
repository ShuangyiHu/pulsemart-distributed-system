import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import client from '../api/client';

export default function PlaceOrderPage() {
  const { customerId } = useAuth();
  const navigate = useNavigate();
  const [items, setItems] = useState([
    { productId: '', productName: '', quantity: 1, unitPrice: '' },
  ]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const updateItem = (index, field, value) => {
    const updated = [...items];
    updated[index] = { ...updated[index], [field]: value };
    setItems(updated);
  };

  const addItem = () => {
    setItems([...items, { productId: '', productName: '', quantity: 1, unitPrice: '' }]);
  };

  const removeItem = (index) => {
    if (items.length > 1) {
      setItems(items.filter((_, i) => i !== index));
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const payload = {
        customerId,
        items: items.map((item) => ({
          productId: item.productId,
          productName: item.productName,
          quantity: parseInt(item.quantity, 10),
          unitPrice: parseFloat(item.unitPrice),
        })),
      };
      const res = await client.post('/orders', payload);
      navigate(`/orders/${res.data.orderId}`);
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to place order');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="page">
      <h2>Place Order</h2>
      <div className="card">
        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label>Customer ID</label>
            <input type="text" value={customerId || ''} disabled />
          </div>

          <h3>Items</h3>
          {items.map((item, i) => (
            <div key={i} className="item-row">
              <div className="form-group">
                <label>Product ID</label>
                <input
                  type="text"
                  value={item.productId}
                  onChange={(e) => updateItem(i, 'productId', e.target.value)}
                  placeholder="UUID"
                  required
                />
              </div>
              <div className="form-group">
                <label>Name</label>
                <input
                  type="text"
                  value={item.productName}
                  onChange={(e) => updateItem(i, 'productName', e.target.value)}
                  placeholder="Widget Pro"
                />
              </div>
              <div className="form-group" style={{ width: 100 }}>
                <label>Qty</label>
                <input
                  type="number"
                  min="1"
                  value={item.quantity}
                  onChange={(e) => updateItem(i, 'quantity', e.target.value)}
                  required
                />
              </div>
              <div className="form-group" style={{ width: 120 }}>
                <label>Unit Price</label>
                <input
                  type="number"
                  step="0.01"
                  min="0.01"
                  value={item.unitPrice}
                  onChange={(e) => updateItem(i, 'unitPrice', e.target.value)}
                  required
                />
              </div>
              {items.length > 1 && (
                <button type="button" className="btn btn-small btn-danger" onClick={() => removeItem(i)}>
                  Remove
                </button>
              )}
            </div>
          ))}

          <button type="button" className="btn btn-small" onClick={addItem} style={{ marginBottom: 16 }}>
            + Add Item
          </button>

          {error && <p className="error">{error}</p>}
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Placing...' : 'Place Order'}
          </button>
        </form>
      </div>
    </div>
  );
}
