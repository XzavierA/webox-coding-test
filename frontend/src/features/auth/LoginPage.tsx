import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { ProblemError } from '../../api/client';
import { useAuth } from './AuthProvider';

const loginSchema = z.object({
  email: z.string().email('Enter a valid email address.').max(200, 'Email must be 200 characters or fewer.'),
  password: z.string().min(1, 'Password is required.').max(72, 'Password must be 72 characters or fewer.'),
});
type LoginValues = z.infer<typeof loginSchema>;

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [serverError, setServerError] = useState('');
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
  });

  const submit = handleSubmit(async (values) => {
    setServerError('');
    try {
      const actor = await login(values);
      const destination = (location.state as { from?: string } | null)?.from;
      navigate(destination || (actor.role === 'ADMIN' ? '/console/dashboard' : '/menu'), { replace: true });
    } catch (error) {
      setServerError(error instanceof ProblemError && error.problem.status === 429
        ? 'Too many sign-in attempts. Try again shortly.'
        : 'Email or password is incorrect.');
    }
  });

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="login-title">
        <div className="brand-mark" aria-hidden="true">W</div>
        <p className="eyebrow">WELCOME BACK</p>
        <h1 id="login-title">Good food starts here.</h1>
        <p className="auth-intro">Sign in to explore today’s thoughtfully made menu.</p>
        <form onSubmit={submit} noValidate>
          <label htmlFor="email">Email address</label>
          <input id="email" type="email" autoComplete="email" {...register('email')} aria-invalid={Boolean(errors.email)} />
          {errors.email && <span className="field-error">{errors.email.message}</span>}
          <label htmlFor="password">Password</label>
          <input id="password" type="password" autoComplete="current-password" {...register('password')} aria-invalid={Boolean(errors.password)} />
          {errors.password && <span className="field-error">{errors.password.message}</span>}
          {serverError && <p className="form-error" role="alert">{serverError}</p>}
          <button className="button button-primary auth-submit" type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p className="auth-switch">New to WeBox? <Link to="/register">Create an account</Link></p>
      </section>
      <aside className="auth-art" aria-label="Freshly prepared lunch ingredients">
        <div className="art-copy"><span>Freshly made.</span><span>Ready when you are.</span></div>
        <div className="art-orbit orbit-one" /><div className="art-orbit orbit-two" />
        <div className="art-plate"><div className="plate-leaf leaf-one" /><div className="plate-leaf leaf-two" /><div className="plate-orange" /></div>
        <p className="art-caption">A better lunch, one good choice at a time.</p>
      </aside>
    </main>
  );
}
