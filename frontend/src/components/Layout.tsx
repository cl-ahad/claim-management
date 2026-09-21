import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import type { ReactNode } from 'react';

export function Layout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const signOut = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">CM</div>
          <div>
            <div className="brand-text">Claim Management</div>
            <div className="brand-sub">Phase 1 &middot; v1.0.0</div>
          </div>
        </div>

        <nav className="nav">
          <div className="nav-label">Claims</div>
          <NavLink to="/" end>Dashboard</NavLink>
          <NavLink to="/claims">All claims</NavLink>
          <NavLink to="/claims/new">New claim</NavLink>
          <NavLink to="/reports">Reports</NavLink>

          <div className="nav-label">Reference data</div>
          <NavLink to="/patients">Patients</NavLink>
          <NavLink to="/payers">Payers</NavLink>
          <NavLink to="/providers">Providers</NavLink>

          <div className="nav-label">Next phase</div>
          <NavLink to="/roadmap">Phase 2 roadmap</NavLink>
        </nav>

        <div className="sidebar-footer">
          <strong>{user?.fullName}</strong>
          <span>{user?.role}</span>
          <div style={{ marginTop: 10 }}>
            <button className="btn btn-secondary btn-sm" onClick={signOut}>Sign out</button>
          </div>
        </div>
      </aside>

      <div className="main">
        <Outlet />
      </div>
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }:
  { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="topbar">
      <div className="topbar-title">
        <h1>{title}</h1>
        {subtitle && <span className="topbar-sub">{subtitle}</span>}
      </div>
      {actions && <div className="btn-row">{actions}</div>}
    </header>
  );
}
