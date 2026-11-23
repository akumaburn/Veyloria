import React from 'react';
import { AdminDashboard } from '../components/admin/AdminDashboard';

/**
 * AdminDashboard owns the page frame and its header, because the refresh
 * controls and the section tabs share the same state.
 */
const AdminPage: React.FC = () => <AdminDashboard />;

export default AdminPage;
