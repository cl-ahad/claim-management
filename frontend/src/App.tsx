import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import ClaimsListPage from './pages/ClaimsListPage';
import ClaimDetailPage from './pages/ClaimDetailPage';
import ClaimFormPage from './pages/ClaimFormPage';
import PatientsPage from './pages/PatientsPage';
import PatientDetailPage from './pages/PatientDetailPage';
import PayersPage from './pages/PayersPage';
import ProvidersPage from './pages/ProvidersPage';
import ReportsPage from './pages/ReportsPage';
import RoadmapPage from './pages/RoadmapPage';
import type { ReactElement } from 'react';

function RequireAuth({ children }: { children: ReactElement }) {
  const { user } = useAuth();
  return user ? children : <Navigate to="/login" replace />;
}

function Router() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/claims" element={<ClaimsListPage />} />
        <Route path="/claims/new" element={<ClaimFormPage mode="create" />} />
        <Route path="/claims/:id" element={<ClaimDetailPage />} />
        <Route path="/claims/:id/edit" element={<ClaimFormPage mode="edit" />} />
        <Route path="/patients" element={<PatientsPage />} />
        <Route path="/patients/:id" element={<PatientDetailPage />} />
        <Route path="/payers" element={<PayersPage />} />
        <Route path="/providers" element={<ProvidersPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/roadmap" element={<RoadmapPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <HashRouter>
        <Router />
      </HashRouter>
    </AuthProvider>
  );
}
